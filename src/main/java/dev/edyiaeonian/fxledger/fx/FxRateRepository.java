package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FxRateRepository {

    private final JdbcClient jdbc;

    FxRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a day's rates. A day already stored is left as it is: the ECB
     * does not revise a publication, and the hourly refresh fetches the same
     * day many times.
     */
    void insert(RateSnapshot snapshot, Instant fetchedAt) {
        snapshot.perEuro().forEach((currency, rate) -> jdbc.sql("""
                        INSERT INTO fx_rates (rate_date, currency, rate, fetched_at)
                        VALUES (:date, :currency, :rate, :fetchedAt)
                        ON CONFLICT (rate_date, currency) DO NOTHING
                        """)
                .param("date", snapshot.date())
                .param("currency", currency.getCurrencyCode())
                .param("rate", rate)
                .param("fetchedAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
                .update());
    }

    /** The most recent day on or before {@code day} that has rates. */
    Optional<LocalDate> latestDateOnOrBefore(LocalDate day) {
        return jdbc.sql("SELECT max(rate_date) FROM fx_rates WHERE rate_date <= :day")
                .param("day", day)
                .query(LocalDate.class)
                .optional();
    }

    RateSnapshot ratesOn(LocalDate date) {
        Map<Currency, BigDecimal> perEuro = new HashMap<>();
        jdbc.sql("SELECT currency, rate FROM fx_rates WHERE rate_date = :date")
                .param("date", date)
                .query((row, n) -> perEuro.put(Currency.getInstance(row.getString("currency")), row.getBigDecimal("rate")))
                .list();
        return new RateSnapshot(date, perEuro);
    }
}
