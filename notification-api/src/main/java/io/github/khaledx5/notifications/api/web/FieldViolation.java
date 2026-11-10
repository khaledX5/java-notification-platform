package io.github.khaledx5.notifications.api.web;

/** One rejected request field, listed under {@code errors} in a validation problem. */
record FieldViolation(String field, String message) {}
