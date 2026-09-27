package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable of rates and quotes, bound from {@code fxledger.fx.*}.
 *
 * <p>Checked when the application starts: a fee of 150% or a negative quote
 * lifetime fails startup instead of pricing a quote.
 */
@ConfigurationProperties("fxledger.fx")
public record FxProperties(
        @DefaultValue("https://api.frankfurter.dev") URI baseUrl,
        @DefaultValue("true") boolean refreshEnabled,
        @DefaultValue("1h") Duration refreshInterval,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        // Rates older than this stop new quotes. The ECB publishes nothing at
        // weekends or on TARGET holidays, and the longest such gap is Easter:
        // Thursday's rates are the latest until Tuesday afternoon, five days
        // later (Christmas on a Monday and Tuesday does the same). Four days
        // stopped quoting every Easter Tuesday morning.
        @DefaultValue("5d") Duration maxRateAge,
        // A fraction of the source amount: 0.005 is 0.5%.
        @DefaultValue("0.005") BigDecimal feeRate,
        @DefaultValue("10m") Duration quoteTtl) {

    public FxProperties {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(feeRate, "feeRate");
        if (feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("fxledger.fx.fee-rate must be in [0, 1), got " + feeRate);
        }
        for (Duration duration : new Duration[] {refreshInterval, connectTimeout, readTimeout, maxRateAge, quoteTtl}) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("fxledger.fx durations must be positive");
            }
        }
    }
}
