package dev.edyiaeonian.fxledger.fx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Refreshes the rates once at startup and then every {@code refresh-interval}.
 * Absent when {@code fxledger.fx.refresh-enabled=false}, as in the tests.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "fxledger.fx", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
class RateRefreshScheduler {

    private final FxRateService rates;

    RateRefreshScheduler(FxRateService rates) {
        this.rates = rates;
    }

    // fixedDelay, not fixedRate: the next refresh is timed from the end of
    // the last one, so a slow API never causes refreshes to pile up.
    @Scheduled(initialDelay = 0, fixedDelayString = "${fxledger.fx.refresh-interval:1h}")
    void refresh() {
        rates.refresh();
    }
}
