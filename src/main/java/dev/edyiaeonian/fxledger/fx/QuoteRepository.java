package dev.edyiaeonian.fxledger.fx;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.edyiaeonian.fxledger.money.Money;

@Repository
class QuoteRepository {

    private final JdbcClient jdbc;

    QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Quote quote) {
        jdbc.sql("""
                INSERT INTO quotes (id, source_currency, target_currency, source_amount, fee, rate,
                                    target_amount, rate_date, created_at, expires_at, status)
                VALUES (:id, :sourceCurrency, :targetCurrency, :sourceAmount, :fee, :rate,
                        :targetAmount, :rateDate, :createdAt, :expiresAt, :status)
                """)
                .param("id", quote.id())
                .param("sourceCurrency", quote.source().currency().getCurrencyCode())
                .param("targetCurrency", quote.target().currency().getCurrencyCode())
                .param("sourceAmount", quote.source().minorUnits())
                .param("fee", quote.fee().minorUnits())
                .param("rate", quote.rate())
                .param("targetAmount", quote.target().minorUnits())
                .param("rateDate", quote.rateDate())
                .param("createdAt", OffsetDateTime.ofInstant(quote.createdAt(), ZoneOffset.UTC))
                .param("expiresAt", OffsetDateTime.ofInstant(quote.expiresAt(), ZoneOffset.UTC))
                .param("status", quote.status().name())
                .update();
    }

    Optional<Quote> findById(UUID id) {
        return select("SELECT * FROM quotes WHERE id = :id", id);
    }

    /**
     * Locks the quote until the transaction ends, so two transfers cannot both
     * use it. FOR NO KEY UPDATE for the same reason as accounts: the transfer
     * row's foreign key to the quote takes a KEY SHARE lock first.
     */
    Optional<Quote> lockById(UUID id) {
        return select("SELECT * FROM quotes WHERE id = :id FOR NO KEY UPDATE", id);
    }

    void markUsed(UUID id) {
        jdbc.sql("UPDATE quotes SET status = 'USED' WHERE id = :id").param("id", id).update();
    }

    private Optional<Quote> select(String sql, UUID id) {
        return jdbc.sql(sql)
                .param("id", id)
                .query((row, n) -> {
                    Currency source = Currency.getInstance(row.getString("source_currency"));
                    Currency target = Currency.getInstance(row.getString("target_currency"));
                    return new Quote(
                            row.getObject("id", UUID.class),
                            Money.ofMinor(row.getLong("source_amount"), source),
                            Money.ofMinor(row.getLong("fee"), source),
                            row.getBigDecimal("rate"),
                            Money.ofMinor(row.getLong("target_amount"), target),
                            row.getObject("rate_date", LocalDate.class),
                            row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            QuoteStatus.valueOf(row.getString("status")));
                })
                .optional();
    }
}
