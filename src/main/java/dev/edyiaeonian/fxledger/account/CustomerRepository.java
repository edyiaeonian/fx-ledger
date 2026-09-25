package dev.edyiaeonian.fxledger.account;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CustomerRepository {

    private final JdbcClient jdbc;

    CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Customer customer) {
        jdbc.sql("INSERT INTO customers (id, name, created_at) VALUES (:id, :name, :createdAt)")
                .param("id", customer.id())
                .param("name", customer.name())
                .param("createdAt", OffsetDateTime.ofInstant(customer.createdAt(), ZoneOffset.UTC))
                .update();
    }

    Optional<Customer> findById(UUID id) {
        return jdbc.sql("SELECT id, name, created_at FROM customers WHERE id = :id")
                .param("id", id)
                .query((row, rowNumber) -> new Customer(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
