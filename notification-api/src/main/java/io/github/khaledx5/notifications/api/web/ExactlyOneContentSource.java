package io.github.khaledx5.notifications.api.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A notification request carries its own {@code body} or names a {@code templateKey}, never both and
 * never neither. With a template, {@code subject} and {@code body} come from the template; without
 * one, {@code variables} have nothing to fill in.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExactlyOneContentSource.Validator.class)
@interface ExactlyOneContentSource {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ExactlyOneContentSource, CreateNotificationRequest> {

        @Override
        public boolean isValid(CreateNotificationRequest request, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();
            boolean hasBody = request.body() != null && !request.body().isBlank();

            if (request.usesTemplate()) {
                boolean valid = true;
                if (request.body() != null) {
                    valid = reject(context, "body", "must not be set together with templateKey");
                }
                if (request.subject() != null) {
                    valid = reject(context, "subject", "must not be set together with templateKey");
                }
                return valid;
            }
            if (!hasBody) {
                return reject(context, "body", "must not be blank unless templateKey is set");
            }
            if (request.variables() != null && !request.variables().isEmpty()) {
                return reject(context, "variables", "must not be set without templateKey");
            }
            return true;
        }

        private static boolean reject(ConstraintValidatorContext context, String field, String message) {
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode(field)
                    .addConstraintViolation();
            return false;
        }
    }
}
