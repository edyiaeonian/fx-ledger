package dev.edyiaeonian.fxledger.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock with one the test controls. @Primary makes it the
 * Clock every component receives, although the real one is still defined.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

    public static final Instant START = Instant.parse("2026-09-25T12:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
