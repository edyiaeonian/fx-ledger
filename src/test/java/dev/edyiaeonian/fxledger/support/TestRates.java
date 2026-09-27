package dev.edyiaeonian.fxledger.support;

import dev.edyiaeonian.fxledger.fx.RateSnapshot;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/** A complete, made-up day of rates: round numbers for the currencies tests calculate with. */
public final class TestRates {

    private TestRates() {}

    public static RateSnapshot on(LocalDate date) {
        Map<Currency, BigDecimal> perEuro = new HashMap<>();
        for (String code : SupportedCurrencies.codes()) {
            if (!code.equals("EUR")) {
                perEuro.put(Currency.getInstance(code), new BigDecimal("2"));
            }
        }
        perEuro.put(Currency.getInstance("GBP"), new BigDecimal("0.85"));
        perEuro.put(Currency.getInstance("USD"), new BigDecimal("1.1367"));
        perEuro.put(Currency.getInstance("JPY"), new BigDecimal("162.345"));
        return new RateSnapshot(date, perEuro);
    }
}
