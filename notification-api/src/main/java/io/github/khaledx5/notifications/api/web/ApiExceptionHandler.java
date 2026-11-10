package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.application.NotificationNotFoundException;
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
                String parameter = result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(error -> violations.add(new FieldViolation(parameter, error.getDefaultMessage())));
            }
        }
        return validationProblem(ex, ex.getBody(), violations, headers, status, request);
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
