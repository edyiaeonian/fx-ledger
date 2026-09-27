package dev.edyiaeonian.fxledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.deposit.DepositService;
import dev.edyiaeonian.fxledger.ledger.LedgerService.PostingRequest;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Many threads at once against the same accounts. Each test releases every
 * thread at the same moment (a start gate), so the requests truly overlap
 * instead of running one after another.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerConcurrencyTest {

    static final Currency EUR = Currency.getInstance("EUR");
    static final int THREADS = 16;

    @Autowired
    AccountService accounts;

    @Autowired
    DepositService deposits;

    @Autowired
    LedgerService ledger;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    UUID fundedAccount(long minor) {
        UUID customer = accounts.createCustomer("concurrency").id();
        UUID account = accounts.openAccount(customer, "EUR").id();
        if (minor > 0) {
            deposits.deposit(UUID.randomUUID().toString(), account, Money.ofMinor(minor, EUR));
        }
        return account;
    }

    long balance(UUID account) {
        return accounts.findAccount(account).orElseThrow().balance().minorUnits();
    }

    void move(UUID from, UUID to, long minor) {
        transaction.executeWithoutResult(status -> ledger.post(
                UUID.randomUUID(),
                EntryType.TRANSFER,
                List.of(
                        new PostingRequest(from, Money.ofMinor(-minor, EUR)),
                        new PostingRequest(to, Money.ofMinor(minor, EUR)))));
    }

    /** Runs each task on its own thread, all released at once; returns what each threw, if anything. */
    List<Throwable> runTogether(List<Runnable> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        task.run();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        return failures;
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        // A -> B and B -> A at the same time is the classic deadlock: each side
        // holds one lock and waits for the other. Locking in id order means
        // both take the same lock first, so one simply waits for the other.
        UUID a = fundedAccount(1_000_000);
        UUID b = fundedAccount(1_000_000);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> {
                for (int n = 0; n < 20; n++) {
                    if (forward) {
                        move(a, b, 100);
                    } else {
                        move(b, a, 100);
                    }
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).as("no request may fail, least of all with a deadlock").isEmpty();
        assertThat(balance(a) + balance(b)).isEqualTo(2_000_000);
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void racingWithdrawalsNeverOverdraw() throws Exception {
        // 100.00 in the account, and 16 threads each trying to take 10.00 at
        // once: exactly 10 can succeed. Without the row lock, several threads
        // would read the same balance and each think there was enough.
        UUID source = fundedAccount(10_000);
        UUID sink = fundedAccount(0);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                try {
                    move(source, sink, 1_000);
                    succeeded.incrementAndGet();
                } catch (DomainException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    refused.incrementAndGet();
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        assertThat(succeeded).hasValue(10);
        assertThat(refused).hasValue(THREADS - 10);
        assertThat(balance(source)).isZero();
        assertThat(balance(sink)).isEqualTo(10_000);
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void oneIdempotencyKeyDepositsOnceEvenWhenSentManyTimesAtOnce() throws Exception {
        UUID account = fundedAccount(0);
        String key = UUID.randomUUID().toString();
        Set<UUID> depositIds = ConcurrentHashMap.newKeySet();
        AtomicInteger replays = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                DepositService.Result result = deposits.deposit(key, account, Money.ofMinor(5_000, EUR));
                depositIds.add(result.deposit().id());
                if (result.replayed()) {
                    replays.incrementAndGet();
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        assertThat(depositIds).as("every caller sees the same deposit").hasSize(1);
        assertThat(replays).hasValue(THREADS - 1);
        assertThat(balance(account)).isEqualTo(5_000);
        long rows = jdbc.sql("SELECT count(*) FROM deposits WHERE idempotency_key = :key")
                .param("key", key)
                .query(Long.class)
                .single();
        assertThat(rows).isEqualTo(1);
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void theSystemFundingAccountSurvivesManyDepositsAtOnce() throws Exception {
        // Every deposit in a currency also writes to its one FUNDING account,
        // so all of them contend for that single row.
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            targets.add(fundedAccount(0));
        }
        UUID funding = accounts.systemAccountId(AccountType.FUNDING, EUR);
        long fundingBefore = balance(funding);
        List<Runnable> tasks = new ArrayList<>();
        for (UUID target : targets) {
            tasks.add(() -> {
                for (int n = 0; n < 10; n++) {
                    deposits.deposit(UUID.randomUUID().toString(), target, Money.ofMinor(100, EUR));
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        assertThat(balance(funding)).isEqualTo(fundingBefore - (long) THREADS * 10 * 100);
        LedgerInvariants.assertHold(jdbc);
    }
}
