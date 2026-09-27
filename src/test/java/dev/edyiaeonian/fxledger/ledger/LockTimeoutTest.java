package dev.edyiaeonian.fxledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

// A short lock timeout, so the test does not wait the default five seconds.
@SpringBootTest(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '300ms'")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LockTimeoutTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccountService accounts;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aRequestThatCannotGetItsLockFailsWithARetryableError() throws Exception {
        UUID customer = accounts.createCustomer("lock timeout").id();
        UUID account = accounts.openAccount(customer, "EUR").id();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // Another transaction takes the account's lock and holds it.
            Future<?> holder = pool.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("SELECT id FROM accounts WHERE id = :id FOR UPDATE").param("id", account).query().listOfRows();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await(10, TimeUnit.SECONDS);

            MvcTestResult result = mvc.post()
                    .uri("/accounts/" + account + "/deposits")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": \"10.00\", \"currency\": \"EUR\"}")
                    .exchange();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("LOCK_TIMEOUT");
        }
        // Nothing was deposited, and the request can be retried.
        assertThat(accounts.findAccount(account).orElseThrow().balance().isZero()).isTrue();
    }
}
