package io.github.khaledx5.notifications.api.web;

import io.github.khaledx5.notifications.api.domain.Channel;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * The recipient of an email has to look like an email address. Without this check a request to
 * "Dina" was accepted, tried by the worker until it gave up, and only then reported as failed; now
 * the client is told at once which field is wrong.
 *
 * <p>Only the form is checked here. Whether the mailbox exists is for the mail server to say. Phone
 * numbers and device tokens are left to the sender of their channel, because which ones a gateway
 * accepts is the provider's business.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidEmailRecipient.Validator.class)
@interface ValidEmailRecipient {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidEmailRecipient, CreateNotificationRequest> {

        static final String MESSAGE = "must be an email address, such as dina@example.com";

        /** A local part, an at sign and a domain of at least two labels; no spaces, no second at sign. */
        private static final Pattern EMAIL_ADDRESS = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");

        @Override
        public boolean isValid(CreateNotificationRequest request, ConstraintValidatorContext context) {
            // A missing channel or a blank recipient is already reported by the constraints on the fields.
            if (request.channel() != Channel.EMAIL
                    || request.recipient() == null
                    || request.recipient().isBlank()) {
                return true;
            }
            if (EMAIL_ADDRESS.matcher(request.recipient()).matches()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(MESSAGE)
                    .addPropertyNode("recipient")
                    .addConstraintViolation();
            return false;
        }
    }
}
