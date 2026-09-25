package dev.edyiaeonian.fxledger.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
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
