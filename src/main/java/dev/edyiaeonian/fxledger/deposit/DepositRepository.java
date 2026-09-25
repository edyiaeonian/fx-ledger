package dev.edyiaeonian.fxledger.deposit;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.edyiaeonian.fxledger.money.Money;

@Repository
class DepositRepository {

    record Stored(Deposit deposit, String requestHash) {}

    private final JdbcClient jdbc;

    DepositRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key by inserting the deposit; false if the key
     * is already taken.
     *
     * <p>If another transaction has inserted the same key but not yet
     * committed, PostgreSQL makes this wait for it: if it commits, the key is
     * taken; if it rolls back, this insert goes ahead. Two requests can
     * therefore never both claim one key.
     */
    boolean claim(String idempotencyKey, String requestHash, Deposit deposit) {
        int inserted = jdbc.sql("""
                INSERT INTO deposits (id, idempotency_key, request_hash, account_id, currency, amount, entry_id, created_at)
                VALUES (:id, :key, :hash, :accountId, :currency, :amount, :entryId, :createdAt)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", deposit.id())
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("accountId", deposit.accountId())
                .param("currency", deposit.amount().currency().getCurrencyCode())
                .param("amount", deposit.amount().minorUnits())
                .param("entryId", deposit.entryId())
                .param("createdAt", OffsetDateTime.ofInstant(deposit.createdAt(), ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    Optional<Stored> findByKey(String idempotencyKey) {
        return jdbc.sql("""
                SELECT id, account_id, currency, amount, entry_id, created_at, request_hash
                FROM deposits
                WHERE idempotency_key = :key
                """)
                .param("key", idempotencyKey)
                .query((row, n) -> new Stored(
                        new Deposit(
                                row.getObject("id", UUID.class),
                                row.getObject("account_id", UUID.class),
                                Money.ofMinor(row.getLong("amount"), Currency.getInstance(row.getString("currency"))),
                                row.getObject("entry_id", UUID.class),
                                row.getObject("created_at", OffsetDateTime.class).toInstant()),
                        row.getString("request_hash")))
                .optional();
    }
}
