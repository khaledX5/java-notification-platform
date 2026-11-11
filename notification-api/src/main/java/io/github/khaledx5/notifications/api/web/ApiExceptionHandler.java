package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.IdempotencyKeyReusedException;
import io.github.khaledx5.notifications.api.application.IdempotencyUnavailableException;
import io.github.khaledx5.notifications.api.application.NotificationNotFoundException;
import io.github.khaledx5.notifications.api.application.NotificationNotQueuedException;
import io.github.khaledx5.notifications.api.application.RequestInProgressException;
import io.github.khaledx5.notifications.api.application.TemplateNotFoundException;
import io.github.khaledx5.notifications.api.application.UnknownTemplateException;
import io.github.khaledx5.notifications.api.domain.MissingTemplateVariableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem document ({@code application/problem+json}).
 *
 * <p>Spring MVC's own exceptions (unreadable body, type mismatch, unsupported media type, ...) are
 * handled by the base class. This class adds the field list for validation errors, the application's
 * own exceptions, and a catch-all that never leaks internal details.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String ERRORS_PROPERTY = "errors";
    static final String MISSING_VARIABLES_PROPERTY = "missingVariables";
    static final String NOTIFICATION_ID_PROPERTY = "notificationId";

    /** The request is well-formed, but refers to something that makes it impossible to carry out. */
    private static final HttpStatusCode UNPROCESSABLE = HttpStatusCode.valueOf(422);

    static final String INVALID_REQUEST_DETAIL = "Invalid request content.";

    @ExceptionHandler(NotificationNotFoundException.class)
    ProblemDetail handleNotFound(NotificationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Notification not found");
        return problem;
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    ProblemDetail handleNotFound(TemplateNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Template not found");
        return problem;
    }

    @ExceptionHandler(UnknownTemplateException.class)
    ProblemDetail handleUnknownTemplate(UnknownTemplateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(UNPROCESSABLE, ex.getMessage());
        problem.setTitle("Unknown template");
        return problem;
    }

    @ExceptionHandler(MissingTemplateVariableException.class)
    ProblemDetail handleMissingVariables(MissingTemplateVariableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(UNPROCESSABLE, ex.getMessage());
        problem.setTitle("Missing template variables");
        problem.setProperty(MISSING_VARIABLES_PROPERTY, ex.missingVariables());
        return problem;
    }

    /** The broker is unreachable; the client may try again later. */
    @ExceptionHandler(NotificationNotQueuedException.class)
    ProblemDetail handleNotQueued(NotificationNotQueuedException ex) {
        log.error("Notification {} could not be queued", ex.notificationId(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The notification could not be queued for delivery. Try again later.");
        problem.setTitle("Notification not queued");
        problem.setProperty(NOTIFICATION_ID_PROPERTY, ex.notificationId());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleKeyReused(IdempotencyKeyReusedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                UNPROCESSABLE, "This Idempotency-Key was already used with a different request body.");
        problem.setTitle("Idempotency key reused");
        return problem;
    }

    @ExceptionHandler(RequestInProgressException.class)
    ProblemDetail handleInProgress(RequestInProgressException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The first request with this Idempotency-Key is still being processed. Try again shortly.");
        problem.setTitle("Request in progress");
        return problem;
    }

    /** The key could not be checked, so the request was not run; repeating it later is safe. */
    @ExceptionHandler(IdempotencyUnavailableException.class)
    ProblemDetail handleIdempotencyUnavailable(IdempotencyUnavailableException ex) {
        log.error("An idempotency key could not be checked", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The Idempotency-Key could not be checked. Try again later.");
        problem.setTitle("Idempotency check unavailable");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing a request", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationProblem(ex, ex.getBody(), violations, headers, status, request);
    }

    /**
     * Raised instead of {@link MethodArgumentNotValidException} when a handler method also has
     * constraints directly on a parameter, such as a path variable.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors
                        .getFieldErrors()
                        .forEach(error ->
                                violations.add(new FieldViolation(error.getField(), error.getDefaultMessage())));
            } else {
                String parameter = nameOf(result);
                result.getResolvableErrors()
                        .forEach(error -> violations.add(new FieldViolation(parameter, error.getDefaultMessage())));
            }
        }
        return validationProblem(ex, ex.getBody(), violations, headers, status, request);
    }

    /** A header is reported under the name the client sent it with, not the Java parameter name. */
    private static String nameOf(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        return result.getMethodParameter().getParameterName();
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex,
            ProblemDetail problem,
            List<FieldViolation> violations,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        problem.setDetail(INVALID_REQUEST_DETAIL);
        problem.setProperty(
                ERRORS_PROPERTY,
                violations.stream()
                        .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                        .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
