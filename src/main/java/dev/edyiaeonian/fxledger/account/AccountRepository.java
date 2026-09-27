package dev.edyiaeonian.fxledger.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.edyiaeonian.fxledger.money.Money;

@Repository
class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws DuplicateKeyException if the customer already has this currency. */
    void insert(Account account) {
        jdbc.sql("""
                INSERT INTO accounts (id, customer_id, currency, type, balance, created_at)
                VALUES (:id, :customerId, :currency, :type, :balance, :createdAt)
                """)
                .param("id", account.id())
                .param("customerId", account.customerId())
                .param("currency", account.balance().currency().getCurrencyCode())
                .param("type", account.type().name())
                .param("balance", account.balance().minorUnits())
                .param("createdAt", OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC))
                .update();
    }

    List<Account> findByCustomer(UUID customerId) {
        return jdbc.sql("""
                SELECT id, customer_id, currency, type, balance, created_at
                FROM accounts
                WHERE customer_id = :customerId
                ORDER BY currency
                """)
                .param("customerId", customerId)
                .query(AccountRepository::toAccount)
                .list();
    }

    Optional<Account> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, customer_id, currency, type, balance, created_at
                FROM accounts
                WHERE id = :id
                """)
                .param("id", id)
                .query(AccountRepository::toAccount)
                .optional();
    }

    /**
     * Locks the row until the transaction ends; empty if there is no such account.
     *
     * <p>FOR NO KEY UPDATE, not FOR UPDATE: only the balance changes, never
     * the key. The difference matters because inserting a deposit or transfer
     * row checks its foreign key to the account by taking a shared KEY SHARE
     * lock on it. FOR UPDATE conflicts with that lock, so two transactions
     * that had each checked a foreign key would each wait for the other to
     * release it: a deadlock. FOR NO KEY UPDATE does not conflict with KEY
     * SHARE, yet still conflicts with itself, so balance updates to one
     * account still happen one at a time.
     */
    Optional<Account> lockById(UUID id) {
        return jdbc.sql("""
                SELECT id, customer_id, currency, type, balance, created_at
                FROM accounts
                WHERE id = :id
                FOR NO KEY UPDATE
                """)
                .param("id", id)
                .query(AccountRepository::toAccount)
                .optional();
    }

    UUID systemAccountId(AccountType type, Currency currency) {
        return jdbc.sql("SELECT id FROM accounts WHERE type = :type AND currency = :currency")
                .param("type", type.name())
                .param("currency", currency.getCurrencyCode())
                .query(UUID.class)
                .single();
    }

    void updateBalance(UUID id, long balance) {
        jdbc.sql("UPDATE accounts SET balance = :balance WHERE id = :id")
                .param("id", id)
                .param("balance", balance)
                .update();
    }

    private static Account toAccount(ResultSet row, int rowNumber) throws SQLException {
        Currency currency = Currency.getInstance(row.getString("currency"));
        return new Account(
                row.getObject("id", UUID.class),
                row.getObject("customer_id", UUID.class),
                AccountType.valueOf(row.getString("type")),
                Money.ofMinor(row.getLong("balance"), currency),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
