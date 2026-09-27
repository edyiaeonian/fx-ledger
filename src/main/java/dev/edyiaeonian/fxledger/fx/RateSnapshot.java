package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;

/**
 * One day's reference rates: how many units of each currency one euro buys.
 * The euro itself is implicit, at exactly 1.
 */
public record RateSnapshot(LocalDate date, Map<Currency, BigDecimal> perEuro) {

    public RateSnapshot {
        Objects.requireNonNull(date, "date");
        perEuro = Map.copyOf(perEuro);
    }
}
