package io.github.khaledx5.notifications.worker.infrastructure.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for outgoing email.
 *
 * @param from address every email is sent from
 */
@ConfigurationProperties("notifications.delivery.email")
record EmailProperties(
        @DefaultValue("no-reply@notifications.local") String from) {}
