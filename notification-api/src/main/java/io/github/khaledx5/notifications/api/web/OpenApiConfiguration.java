package io.github.khaledx5.notifications.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Document-level metadata for the generated OpenAPI description. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String NOTIFICATIONS_TAG = "Notifications";
    static final String TEMPLATES_TAG = "Templates";

    @Bean
    OpenAPI notificationApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification API")
                        .version("v1")
                        .description("""
                                Accepts notification requests for email, SMS and push, and reports their \
                                delivery status. Errors are RFC 9457 problem documents \
                                (application/problem+json).""")
                        .license(new License().name("MIT").url("https://opensource.org/license/mit")));
    }
}
