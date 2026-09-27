package dev.edyiaeonian.fxledger.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TransferRepository {

    record Stored(Transfer transfer, String requestHash) {}

    private final JdbcClient jdbc;

    TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key by inserting the transfer; false if the key
     * is taken. As for deposits, a concurrent insert of the same key waits for
     * the other transaction, so only one of them can claim it.
     *
     * @throws org.springframework.dao.DuplicateKeyException if another
     *     transfer already used this quote
     */
    boolean claim(String idempotencyKey, String requestHash, Transfer transfer) {
        return jdbc.sql("""
                INSERT INTO transfers (id, idempotency_key, request_hash, quote_id, source_account_id,
                                       target_account_id, entry_id, status, created_at)
                VALUES (:id, :key, :hash, :quoteId, :source, :target, :entryId, :status, :createdAt)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", transfer.id())
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("quoteId", transfer.quoteId())
                .param("source", transfer.sourceAccountId())
                .param("target", transfer.targetAccountId())
                .param("entryId", transfer.entryId())
                .param("status", transfer.status())
                .param("createdAt", OffsetDateTime.ofInstant(transfer.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    Optional<Stored> findByKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM transfers WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((row, n) -> new Stored(toTransfer(row, n), row.getString("request_hash")))
                .optional();
    }

    Optional<Transfer> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transfers WHERE id = :id")
                .param("id", id)
                .query(TransferRepository::toTransfer)
                .optional();
    }

    private static Transfer toTransfer(ResultSet row, int rowNumber) throws SQLException {
        return new Transfer(
                row.getObject("id", UUID.class),
                row.getObject("quote_id", UUID.class),
                row.getObject("source_account_id", UUID.class),
                row.getObject("target_account_id", UUID.class),
                row.getObject("entry_id", UUID.class),
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
