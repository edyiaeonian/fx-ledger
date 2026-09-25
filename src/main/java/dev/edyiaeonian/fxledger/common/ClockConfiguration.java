package dev.edyiaeonian.fxledger.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place the application reads the real time. Everything else is
 * given this Clock, so a test can substitute a fixed one.
 */
@Configuration
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
