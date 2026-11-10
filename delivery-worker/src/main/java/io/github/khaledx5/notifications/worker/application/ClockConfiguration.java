package io.github.khaledx5.notifications.worker.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time source for the worker; tests replace it with a fixed clock. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
