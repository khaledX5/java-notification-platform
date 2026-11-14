package io.github.khaledx5.notifications.api.web;

/**
 * Request bodies shown in Swagger UI. Without them the UI fills in every field of the schema at once,
 * so "Try it out" sent a body with both inline content and a template and got a 400 back.
 *
 * <p>Each example is a request the API accepts as it is; {@code OpenApiDocsIT} sends them to make
 * sure they stay that way.
 */
final class RequestExamples {

    static final String INLINE_EMAIL = "inline-email";
    static final String INLINE_EMAIL_JSON = """
            {"recipient": "dina@example.com", "channel": "EMAIL", "subject": "Order shipped", \
            "body": "Your order is on its way"}""";

    static final String TEMPLATE_EMAIL = "template-email";
    static final String TEMPLATE_EMAIL_JSON = """
            {"recipient": "dina@example.com", "channel": "EMAIL", "templateKey": "order-shipped", \
            "variables": {"orderId": "A-1042", "name": "Dina"}}""";

    static final String SMS = "sms";
    static final String SMS_JSON = """
            {"recipient": "+201001234567", "channel": "SMS", "body": "Your code is 4821"}""";

    static final String ORDER_SHIPPED_TEMPLATE = "order-shipped";
    static final String ORDER_SHIPPED_TEMPLATE_JSON = """
            {"subject": "Order {{orderId}} shipped", "body": "Hi {{name}}, order {{orderId}} is on its way."}""";

    private RequestExamples() {}
}
