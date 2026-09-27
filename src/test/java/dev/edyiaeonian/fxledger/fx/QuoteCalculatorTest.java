package dev.edyiaeonian.fxledger.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Pure arithmetic: no Spring, no database, no clock.
class QuoteCalculatorTest {

    static final Currency EUR = Currency.getInstance("EUR");
    static final Currency GBP = Currency.getInstance("GBP");
    static final Currency USD = Currency.getInstance("USD");
    static final Currency JPY = Currency.getInstance("JPY");

    static final RateSnapshot RATES = new RateSnapshot(
            LocalDate.of(2026, 9, 25),
            Map.of(GBP, new BigDecimal("0.85"), USD, new BigDecimal("1.1367"), JPY, new BigDecimal("162.345")));

    static final BigDecimal HALF_PERCENT = new BigDecimal("0.005");

    @Nested
    class CrossRates {

        @Test
        void fromEuroIsThePublishedRate() {
            assertThat(QuoteCalculator.rate(RATES, EUR, GBP)).isEqualByComparingTo("0.85");
        }

        @Test
        void intoEuroIsTheInverse() {
            // 1 / 0.85 = 1.176470588235..., rounded half-even to ten decimals
            assertThat(QuoteCalculator.rate(RATES, GBP, EUR)).isEqualTo(new BigDecimal("1.1764705882"));
        }

        @Test
        void betweenTwoOtherCurrenciesGoesThroughTheEuro() {
            // GBP -> USD = (EUR -> USD) / (EUR -> GBP) = 1.1367 / 0.85 = 1.337294117647...
            assertThat(QuoteCalculator.rate(RATES, GBP, USD)).isEqualTo(new BigDecimal("1.3372941176"));
        }

        @Test
        void alwaysHasTenDecimals() {
            assertThat(QuoteCalculator.rate(RATES, EUR, GBP).scale()).isEqualTo(10);
            assertThat(QuoteCalculator.rate(RATES, USD, USD)).isEqualTo(new BigDecimal("1.0000000000"));
        }

        @Test
        void aCurrencyWithoutARateCannotBeQuoted() {
            Currency chf = Currency.getInstance("CHF");
            assertThatThrownBy(() -> QuoteCalculator.rate(RATES, EUR, chf))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code())
                    .isEqualTo(ErrorCode.RATES_UNAVAILABLE);
        }
    }

    @Nested
    class Pricing {

        @Test
        void theDesignDocumentsExample() {
            // 100.00 EUR at 0.5%: fee 0.50; 99.50 x 0.85 = 84.575 -> 84.57 GBP
            QuoteCalculator.Pricing pricing =
                    QuoteCalculator.price(Money.ofMinor(10000, EUR), GBP, new BigDecimal("0.8500000000"), HALF_PERCENT);

            assertThat(pricing.fee()).isEqualTo(Money.ofMinor(50, EUR));
            assertThat(pricing.target()).isEqualTo(Money.ofMinor(8457, GBP));
        }

        @Test
        void theFeeIsRoundedUpAndTheTargetDown() {
            // 100.01 EUR: fee 0.50005 -> 0.51; 99.50 x 162.345 = 16153.3275 -> 16153 JPY
            QuoteCalculator.Pricing pricing =
                    QuoteCalculator.price(Money.ofMinor(10001, EUR), JPY, new BigDecimal("162.3450000000"), HALF_PERCENT);

            assertThat(pricing.fee()).isEqualTo(Money.ofMinor(51, EUR));
            assertThat(pricing.target()).isEqualTo(Money.ofMinor(16153, JPY));
        }

        @Test
        void aSameCurrencyQuoteOnlyTakesTheFee() {
            QuoteCalculator.Pricing pricing =
                    QuoteCalculator.price(Money.ofMinor(10000, EUR), EUR, new BigDecimal("1.0000000000"), HALF_PERCENT);

            assertThat(pricing.target()).isEqualTo(Money.ofMinor(9950, EUR));
        }

        @Test
        void aZeroFeeRateChargesNothing() {
            QuoteCalculator.Pricing pricing =
                    QuoteCalculator.price(Money.ofMinor(10000, EUR), GBP, new BigDecimal("0.8500000000"), BigDecimal.ZERO);

            assertThat(pricing.fee().isZero()).isTrue();
            assertThat(pricing.target()).isEqualTo(Money.ofMinor(8500, GBP));
        }

        @Test
        void anAmountTheFeeWouldSwallowIsTooSmall() {
            // 0.01 EUR: the fee rounds up to 0.01, leaving nothing to send.
            assertThatThrownBy(() -> QuoteCalculator.price(
                            Money.ofMinor(1, EUR), GBP, new BigDecimal("0.8500000000"), HALF_PERCENT))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code())
                    .isEqualTo(ErrorCode.AMOUNT_TOO_SMALL);
        }

        @Test
        void anAmountThatConvertsToNothingIsTooSmall() {
            // 1 JPY at 0% fee is 0.0061 EUR, which rounds down to 0.00.
            assertThatThrownBy(() -> QuoteCalculator.price(
                            Money.ofMinor(1, JPY), EUR, new BigDecimal("0.0061597215"), BigDecimal.ZERO))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code())
                    .isEqualTo(ErrorCode.AMOUNT_TOO_SMALL);
        }
    }
}
