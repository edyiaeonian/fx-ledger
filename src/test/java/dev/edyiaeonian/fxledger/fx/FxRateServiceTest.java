package dev.edyiaeonian.fxledger.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.support.MutableClock;
import dev.edyiaeonian.fxledger.support.MutableClockConfiguration;
import dev.edyiaeonian.fxledger.support.TestRates;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Its own application context (the mock makes it one), and so its own
// database: the rates stored here cannot leak into other tests' quotes.
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class FxRateServiceTest {

    static final Currency GBP = Currency.getInstance("GBP");

    @Autowired
    FxRateService rates;

    @Autowired
    MutableClock clock;

    @MockitoBean
    RateSource source;

    @BeforeEach
    void resetClock() {
        clock.set(MutableClockConfiguration.START);
    }

    @Test
    void aValidDayOfRatesBecomesCurrent() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        clock.set(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        rates.accept(TestRates.on(day));

        RateSnapshot current = rates.current();

        assertThat(current.date()).isEqualTo(day);
        assertThat(current.perEuro().get(GBP)).isEqualByComparingTo("0.85");
    }

    @Test
    void aRefreshStoresWhatTheSourceReturns() {
        LocalDate day = LocalDate.of(2026, 9, 2);
        clock.set(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        when(source.fetch()).thenReturn(TestRates.on(day));

        assertThat(rates.refresh()).isTrue();
        assertThat(rates.current().date()).isEqualTo(day);
    }

    @Test
    void aFailedRefreshKeepsTheRatesAlreadyStored() {
        LocalDate day = LocalDate.of(2026, 9, 3);
        clock.set(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        rates.accept(TestRates.on(day));
        when(source.fetch()).thenThrow(new RateSourceException("down"));

        assertThat(rates.refresh()).isFalse();
        assertThat(rates.current().date()).isEqualTo(day);
    }

    @Test
    void ratesMissingASupportedCurrencyAreRefusedWhole() {
        LocalDate day = LocalDate.of(2026, 9, 4);
        Map<Currency, BigDecimal> incomplete = new HashMap<>(TestRates.on(day).perEuro());
        incomplete.remove(GBP);

        assertThatThrownBy(() -> rates.accept(new RateSnapshot(day, incomplete)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GBP");
    }

    @Test
    void aRateThatIsNotPositiveIsRefused() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        Map<Currency, BigDecimal> broken = new HashMap<>(TestRates.on(day).perEuro());
        broken.put(GBP, BigDecimal.ZERO);

        assertThatThrownBy(() -> rates.accept(new RateSnapshot(day, broken)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratesDatedInTheFutureAreRefused() {
        LocalDate tomorrowPlusOne = LocalDate.of(2026, 9, 27);

        assertThatThrownBy(() -> rates.accept(TestRates.on(tomorrowPlusOne)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void theLongestGapInEcbPublicationsDoesNotStopQuoting() {
        // Easter 2027: Thursday 25 March's rates are the latest until Tuesday
        // 30 March at about 16:00 CET. That morning they are five days old.
        LocalDate maundyThursday = LocalDate.of(2027, 3, 25);
        clock.set(maundyThursday.atTime(16, 30).toInstant(java.time.ZoneOffset.UTC));
        rates.accept(TestRates.on(maundyThursday));

        clock.set(LocalDate.of(2027, 3, 30).atTime(9, 0).toInstant(java.time.ZoneOffset.UTC));

        assertThat(rates.current().date()).isEqualTo(maundyThursday);
    }

    @Test
    void ratesOlderThanTheLimitStopQuoting() {
        LocalDate day = LocalDate.of(2026, 9, 10);
        clock.set(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC));
        rates.accept(TestRates.on(day));

        clock.advance(Duration.ofDays(5));
        assertThat(rates.current().date()).isEqualTo(day);

        clock.advance(Duration.ofDays(1));
        assertThatThrownBy(rates::current)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo(ErrorCode.RATES_UNAVAILABLE);
    }
}
