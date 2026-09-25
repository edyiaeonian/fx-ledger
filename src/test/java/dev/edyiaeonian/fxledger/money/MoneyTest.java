package dev.edyiaeonian.fxledger.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    static final Currency EUR = Currency.getInstance("EUR");
    static final Currency GBP = Currency.getInstance("GBP");
    static final Currency JPY = Currency.getInstance("JPY"); // no minor unit
    static final Currency BHD = Currency.getInstance("BHD"); // three decimals

    @Nested
    class Parsing {

        @Test
        void storesTheAmountInMinorUnits() {
            assertThat(Money.parse("100.50", EUR).minorUnits()).isEqualTo(10050);
        }

        @Test
        void usesEachCurrencysOwnNumberOfDecimals() {
            assertThat(Money.parse("100", JPY).minorUnits()).isEqualTo(100);
            assertThat(Money.parse("1.234", BHD).minorUnits()).isEqualTo(1234);
        }

        @Test
        void acceptsFewerDecimalsThanTheCurrencyHas() {
            assertThat(Money.parse("7", EUR).minorUnits()).isEqualTo(700);
            assertThat(Money.parse("7.5", EUR).minorUnits()).isEqualTo(750);
        }

        @Test
        void trailingZerosAreNotExtraPrecision() {
            // 10.500 is the value 10.50, which EUR can hold exactly.
            assertThat(Money.parse("10.500", EUR).minorUnits()).isEqualTo(1050);
        }

        @Test
        void refusesRatherThanRoundsASubMinorAmount() {
            assertThatThrownBy(() -> Money.parse("1.005", EUR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("2 decimal");
            assertThatThrownBy(() -> Money.parse("100.5", JPY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsANegativeAmount() {
            assertThat(Money.parse("-1.50", EUR).minorUnits()).isEqualTo(-150);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "abc", "1e3", "1E+3", "1.", ".5", "+1", " 1", "1 ", "1,50", "--1"})
        void refusesAnythingButAPlainDecimal(String text) {
            assertThatThrownBy(() -> Money.parse(text, EUR))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesAnAmountTooLargeToStore() {
            assertThatThrownBy(() -> Money.parse("999999999999999999999", EUR))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesACurrencyWithoutDecimalPlaces() {
            // XXX ("no currency") has no defined minor unit.
            assertThatThrownBy(() -> Money.parse("1", Currency.getInstance("XXX")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Formatting {

        @Test
        void printsTheCurrencysNumberOfDecimals() {
            assertThat(Money.ofMinor(10050, EUR).toDecimalString()).isEqualTo("100.50");
            assertThat(Money.ofMinor(0, EUR).toDecimalString()).isEqualTo("0.00");
            assertThat(Money.ofMinor(-5, EUR).toDecimalString()).isEqualTo("-0.05");
            assertThat(Money.ofMinor(100, JPY).toDecimalString()).isEqualTo("100");
            assertThat(Money.ofMinor(1234, BHD).toDecimalString()).isEqualTo("1.234");
        }

        @Test
        void roundTripsThroughParse() {
            Money money = Money.parse("12345.67", EUR);
            assertThat(Money.parse(money.toDecimalString(), EUR)).isEqualTo(money);
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsSubtractsAndNegates() {
            Money a = Money.ofMinor(1000, EUR);
            Money b = Money.ofMinor(250, EUR);
            assertThat(a.plus(b)).isEqualTo(Money.ofMinor(1250, EUR));
            assertThat(a.minus(b)).isEqualTo(Money.ofMinor(750, EUR));
            assertThat(a.negate()).isEqualTo(Money.ofMinor(-1000, EUR));
        }

        @Test
        void neverMixesCurrencies() {
            assertThatThrownBy(() -> Money.ofMinor(1, EUR).plus(Money.ofMinor(1, GBP)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("GBP");
        }

        @Test
        void overflowIsAnErrorNotAWrapAround() {
            // Plain long arithmetic would silently wrap to a large negative number.
            Money max = Money.ofMinor(Long.MAX_VALUE, EUR);
            assertThatThrownBy(() -> max.plus(Money.ofMinor(1, EUR)))
                    .isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> Money.ofMinor(Long.MIN_VALUE, EUR).negate())
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void knowsItsSign() {
            assertThat(Money.ofMinor(-1, EUR).isNegative()).isTrue();
            assertThat(Money.ofMinor(0, EUR).isNegative()).isFalse();
            assertThat(Money.ofMinor(0, EUR).isZero()).isTrue();
        }

        @Test
        void equalityIsValueAndCurrency() {
            assertThat(Money.ofMinor(100, EUR)).isEqualTo(Money.parse("1.00", EUR));
            assertThat(Money.ofMinor(100, EUR)).isNotEqualTo(Money.ofMinor(100, GBP));
        }
    }

    @Nested
    class Multiplying {

        @Test
        void aFeeRoundedUpNeverUndercharges() {
            BigDecimal feeRate = new BigDecimal("0.005"); // 0.5%
            // 100.00 EUR -> exactly 0.50
            assertThat(Money.ofMinor(10000, EUR).times(feeRate, RoundingMode.UP))
                    .isEqualTo(Money.ofMinor(50, EUR));
            // 100.01 EUR -> 0.50005, rounded up to 0.51
            assertThat(Money.ofMinor(10001, EUR).times(feeRate, RoundingMode.UP))
                    .isEqualTo(Money.ofMinor(51, EUR));
        }

        @Test
        void theRoundingModeDecidesTheLastUnit() {
            Money amount = Money.ofMinor(10001, EUR);
            BigDecimal feeRate = new BigDecimal("0.005");
            assertThat(amount.times(feeRate, RoundingMode.DOWN)).isEqualTo(Money.ofMinor(50, EUR));
        }
    }

    @Nested
    class Converting {

        @Test
        void theDesignDocumentsExampleRoundsDown() {
            // 99.50 EUR x 0.85 = 84.575 GBP -> 84.57
            Money converted = Money.ofMinor(9950, EUR)
                    .convert(new BigDecimal("0.85"), GBP, RoundingMode.DOWN);
            assertThat(converted).isEqualTo(Money.ofMinor(8457, GBP));
        }

        @Test
        void intoACurrencyWithNoDecimals() {
            // 100.00 EUR x 162.345 = 16234.5 JPY -> 16234
            Money converted = Money.ofMinor(10000, EUR)
                    .convert(new BigDecimal("162.345"), JPY, RoundingMode.DOWN);
            assertThat(converted).isEqualTo(Money.ofMinor(16234, JPY));
        }

        @Test
        void intoACurrencyWithThreeDecimals() {
            // 10.00 EUR x 0.4123456 = 4.123456 BHD -> 4.123
            Money converted = Money.ofMinor(1000, EUR)
                    .convert(new BigDecimal("0.4123456"), BHD, RoundingMode.DOWN);
            assertThat(converted).isEqualTo(Money.ofMinor(4123, BHD));
        }

        @Test
        void aRateMustBePositive() {
            Money eur = Money.ofMinor(100, EUR);
            assertThatThrownBy(() -> eur.convert(BigDecimal.ZERO, GBP, RoundingMode.DOWN))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> eur.convert(new BigDecimal("-1"), GBP, RoundingMode.DOWN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
