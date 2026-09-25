package dev.edyiaeonian.fxledger.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of one currency, held as a whole number of its minor unit (cents
 * for EUR, yen for JPY, fils for BHD).
 *
 * <p>An integer cannot hold half a cent, and never passes through binary
 * floating point, where 0.1 + 0.2 is not 0.3. Only multiplication by a rate
 * uses BigDecimal, and every such step names its rounding mode: there is no
 * default for a caller to inherit by accident.
 *
 * <p>Arithmetic that overflows a long throws instead of wrapping around to a
 * negative number.
 */
public record Money(long minorUnits, Currency currency) {

    // Digits, an optional fraction, an optional leading minus. Nothing else:
    // no exponent ("1E+3"), no "+", no spaces, no bare "1." or ".5".
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            // XXX and similar codes have no minor unit to count in.
            throw new IllegalArgumentException(currency + " has no minor unit");
        }
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /**
     * Parses "100.50" as 10050 EUR cents.
     *
     * <p>An amount with more decimals than the currency has is refused, not
     * rounded: rounding would hide an input error. Trailing zeros are not
     * extra decimals, so "10.500" EUR is 10.50.
     */
    public static Money parse(String amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!PLAIN_DECIMAL.matcher(amount).matches()) {
            throw new IllegalArgumentException("not a plain decimal amount: \"" + amount + "\"");
        }
        int digits = fractionDigits(currency);
        BigDecimal value = new BigDecimal(amount);
        BigDecimal minor = value.movePointRight(digits);
        if (minor.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    currency + " allows at most " + digits + " decimal places, got " + amount);
        }
        try {
            return new Money(minor.longValueExact(), currency);
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("amount too large: " + amount, tooLarge);
        }
    }

    /** "100.50" for 10050 EUR cents; "100" for 100 JPY. */
    public String toDecimalString() {
        return BigDecimal.valueOf(minorUnits, fractionDigits(currency)).toPlainString();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    /** This amount times a factor, in the same currency: a percentage fee, for one. */
    public Money times(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(rounding, "rounding");
        BigDecimal result = BigDecimal.valueOf(minorUnits).multiply(factor).setScale(0, rounding);
        return new Money(result.longValueExact(), currency);
    }

    /**
     * This amount in another currency at the given rate: one unit of this
     * currency buys {@code rate} units of the target.
     *
     * <p>The product is computed exactly, then rounded once, to the target's
     * minor unit.
     */
    public Money convert(BigDecimal rate, Currency target, RoundingMode rounding) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(rounding, "rounding");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("a rate must be positive, got " + rate);
        }
        BigDecimal major = BigDecimal.valueOf(minorUnits, fractionDigits(currency));
        BigDecimal targetMinor = major.multiply(rate)
                .setScale(fractionDigits(target), rounding)
                .movePointRight(fractionDigits(target));
        return new Money(targetMinor.longValueExact(), target);
    }

    @Override
    public String toString() {
        return toDecimalString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency);
        }
    }

    private static int fractionDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException(currency + " has no minor unit");
        }
        return digits;
    }
}
