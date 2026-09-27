package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;

/**
 * Keeps the reference rates: fetches them, refuses bad ones, and says which
 * are current.
 *
 * <p>Quotes read rates from the database only, never from the network, so
 * the rate API being down never fails a quote. It only lets the stored rates
 * age, until they pass {@code max-rate-age} and quoting stops.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);

    private final RateSource source;
    private final FxRateRepository repository;
    private final FxProperties properties;
    private final Clock clock;

    FxRateService(RateSource source, FxRateRepository repository, FxProperties properties, Clock clock) {
        this.source = source;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Fetches and stores the latest rates; true if that worked. A failure is
     * logged and the rates already stored stay current.
     */
    public boolean refresh() {
        try {
            accept(source.fetch());
            return true;
        } catch (RateSourceException | IllegalArgumentException failure) {
            log.warn("rate refresh failed; keeping the stored rates: {}", failure.getMessage());
            return false;
        }
    }

    /**
     * Validates a day's rates and stores them. All or nothing: one missing or
     * impossible rate rejects the whole day, since pricing from a partial set
     * could quote one currency from today and another from last week.
     *
     * @throws IllegalArgumentException if the rates are unusable
     */
    @Transactional
    public void accept(RateSnapshot snapshot) {
        // One day of slack, for a publication dated by a clock ahead of UTC.
        LocalDate latestAllowed = today().plusDays(1);
        if (snapshot.date().isAfter(latestAllowed)) {
            throw new IllegalArgumentException("rates dated " + snapshot.date() + " are in the future");
        }
        List<String> missing = SupportedCurrencies.codes().stream()
                .filter(code -> !code.equals("EUR"))
                .filter(code -> !snapshot.perEuro().containsKey(Currency.getInstance(code)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("rates for " + snapshot.date() + " lack " + missing);
        }
        snapshot.perEuro().forEach((currency, rate) -> {
            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("impossible " + currency + " rate: " + rate);
            }
        });
        repository.insert(snapshot, clock.instant());
    }

    /**
     * The rates to price a quote with: the latest day's, unless they are too
     * old to trust.
     *
     * @throws DomainException RATES_UNAVAILABLE if there are none, or they are stale
     */
    @Transactional(readOnly = true)
    public RateSnapshot current() {
        LocalDate today = today();
        LocalDate latest = repository.latestDateOnOrBefore(today)
                .orElseThrow(() -> new DomainException(ErrorCode.RATES_UNAVAILABLE, "no exchange rates are available yet"));
        long age = ChronoUnit.DAYS.between(latest, today);
        if (age > properties.maxRateAge().toDays()) {
            throw new DomainException(
                    ErrorCode.RATES_UNAVAILABLE,
                    "the latest exchange rates, from " + latest + ", are " + age + " days old");
        }
        return repository.ratesOn(latest);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
