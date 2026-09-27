package dev.edyiaeonian.fxledger.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.edyiaeonian.fxledger.money.Money;

@Repository
class LedgerRepository {

    /** One line of a statement, as read back from the ledger. */
    record StatementLine(long postingId, UUID entryId, EntryType type, Money amount, Money balanceAfter,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertEntry(UUID id, EntryType type, Instant createdAt) {
        jdbc.sql("INSERT INTO journal_entries (id, type, created_at) VALUES (:id, :type, :createdAt)")
                .param("id", id)
                .param("type", type.name())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    void insertPosting(UUID entryId, UUID accountId, Money amount, Money balanceAfter, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO postings (entry_id, account_id, currency, amount, balance_after, created_at)
                VALUES (:entryId, :accountId, :currency, :amount, :balanceAfter, :createdAt)
                """)
                .param("entryId", entryId)
                .param("accountId", accountId)
                .param("currency", amount.currency().getCurrencyCode())
                .param("amount", amount.minorUnits())
                .param("balanceAfter", balanceAfter.minorUnits())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    /** Newest first, starting below {@code beforePostingId} when given. */
    List<StatementLine> statement(UUID accountId, Long beforePostingId, int limit) {
        return jdbc.sql("""
                SELECT p.id, p.entry_id, e.type, p.currency, p.amount, p.balance_after, p.created_at
                FROM postings p
                JOIN journal_entries e ON e.id = p.entry_id
                WHERE p.account_id = :accountId
                  AND (CAST(:before AS BIGINT) IS NULL OR p.id < :before)
                ORDER BY p.id DESC
                LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("before", beforePostingId)
                .param("limit", limit)
                .query((row, n) -> {
                    Currency currency = Currency.getInstance(row.getString("currency"));
                    return new StatementLine(
                            row.getLong("id"),
                            row.getObject("entry_id", UUID.class),
                            EntryType.valueOf(row.getString("type")),
                            Money.ofMinor(row.getLong("amount"), currency),
                            Money.ofMinor(row.getLong("balance_after"), currency),
                            row.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .list();
    }
}
