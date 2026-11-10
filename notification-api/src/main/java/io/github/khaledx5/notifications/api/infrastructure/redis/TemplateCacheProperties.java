package io.github.khaledx5.notifications.api.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the Redis template cache.
 *
 * @param cacheTtl how long a cached template is served before it is read from MongoDB again
 */
@ConfigurationProperties("notifications.templates")
record TemplateCacheProperties(@DefaultValue("10m") Duration cacheTtl) {}
