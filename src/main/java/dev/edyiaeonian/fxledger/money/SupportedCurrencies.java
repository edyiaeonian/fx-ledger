package dev.edyiaeonian.fxledger.money;

import java.util.Currency;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;

/**
 * The currencies an account can hold: those the European Central Bank
 * publishes reference rates for, since every quote is priced from them.
 *
 * <p>The list is fixed in code rather than read from the rate feed, so that a
 * currency cannot appear or vanish with one day's download. It was taken
 * from the ECB feed on 2026-09-24.
 */
public final class SupportedCurrencies {

    private static final SortedSet<String> CODES = new TreeSet<>(Set.of(
            "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD",
            "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KRW", "MXN", "MYR", "NOK",
            "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD", "ZAR"));

    private SupportedCurrencies() {}

    /**
     * The currency for an ISO 4217 code, if it is supported.
     *
     * <p>The code must be exactly as ISO writes it: "eur" is refused rather
     * than corrected, like any other malformed input.
     */
    public static Currency require(String code) {
        if (code == null || !CODES.contains(code)) {
            throw new DomainException(
                    ErrorCode.UNSUPPORTED_CURRENCY,
                    "unsupported currency: \"" + code + "\"; supported: " + String.join(", ", CODES));
        }
        return Currency.getInstance(code);
    }

    public static SortedSet<String> codes() {
        return java.util.Collections.unmodifiableSortedSet(CODES);
    }
}
