package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;

/**
 * The arithmetic of a quote, kept free of the database and the clock so that
 * every figure can be checked by hand in a unit test.
 */
final class QuoteCalculator {

    static final Currency EUR = Currency.getInstance("EUR");

    /** A quoted rate carries ten decimals: more than any published rate has. */
    static final int RATE_SCALE = 10;

    record Pricing(Money fee, Money target) {}

    private QuoteCalculator() {}

    /**
     * Units of {@code to} per unit of {@code from}.
     *
     * <p>Every published rate is against the euro, so a pair such as GBP to
     * USD is (EUR to USD) / (EUR to GBP). The division runs at 34 significant
     * digits (DECIMAL128), and only the result is rounded, half-even, to ten
     * decimals.
     */
    static BigDecimal rate(RateSnapshot rates, Currency from, Currency to) {
        if (from.equals(to)) {
            return BigDecimal.ONE.setScale(RATE_SCALE);
        }
        return perEuro(rates, to)
                .divide(perEuro(rates, from), MathContext.DECIMAL128)
                .setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The fee is rounded up and the converted amount down, so the service
     * never pays out more than the arithmetic gives; both figures are shown
     * to the customer before they accept the quote.
     */
    static Pricing price(Money source, Currency target, BigDecimal rate, BigDecimal feeRate) {
        Money fee = source.times(feeRate, RoundingMode.UP);
        Money net = source.minus(fee);
        if (net.isZero() || net.isNegative()) {
            throw new DomainException(
                    ErrorCode.AMOUNT_TOO_SMALL, "the fee of " + fee + " leaves nothing of " + source + " to send");
        }
        Money converted = net.convert(rate, target, RoundingMode.DOWN);
        if (converted.isZero()) {
            throw new DomainException(
                    ErrorCode.AMOUNT_TOO_SMALL, source + " is less than the smallest unit of " + target);
        }
        return new Pricing(fee, converted);
    }

    private static BigDecimal perEuro(RateSnapshot rates, Currency currency) {
        if (currency.equals(EUR)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = rates.perEuro().get(currency);
        if (rate == null) {
            throw new DomainException(
                    ErrorCode.RATES_UNAVAILABLE, "no " + currency + " rate for " + rates.date());
        }
        return rate;
    }
}
