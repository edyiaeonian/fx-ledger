package dev.edyiaeonian.fxledger.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

// The database's own guarantees, checked by bypassing the application: if a
// bug ever skipped the service's checks, these would still hold.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountConstraintsTest {

    @Autowired
    AccountService service;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCustomerAccountCannotGoNegative() {
        UUID customer = service.createCustomer("Constraint check").id();
        UUID account = service.openAccount(customer, "EUR").id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance = -1 WHERE id = :id")
                        .param("id", account)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("customer_balance_not_negative");
    }

    @Test
    void aSystemAccountHasNoCustomer() {
        UUID customer = service.createCustomer("Constraint check").id();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO accounts (id, customer_id, currency, type, balance, created_at)
                        VALUES (:id, :customer, 'EUR', 'FUNDING', 0, now())
                        """)
                        .param("id", UUID.randomUUID())
                        .param("customer", customer)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("owner_matches_type");
    }
}
