package dev.edyiaeonian.fxledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.deposit.DepositService;
import dev.edyiaeonian.fxledger.fx.FxRateService;
import dev.edyiaeonian.fxledger.fx.QuoteService;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;
import dev.edyiaeonian.fxledger.support.LedgerInvariants;
import dev.edyiaeonian.fxledger.support.MutableClock;
import dev.edyiaeonian.fxledger.support.MutableClockConfiguration;
import dev.edyiaeonian.fxledger.support.TestRates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class TransferConcurrencyTest {

    static final int THREADS = 16;

    @Autowired
    MutableClock clock;

    @Autowired
    FxRateService rates;

    @Autowired
    QuoteService quotes;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    DepositService deposits;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void ratesForToday() {
        clock.set(MutableClockConfiguration.START);
        rates.accept(TestRates.on(LocalDate.of(2026, 9, 25)));
    }

    UUID account(String currency, String balance) {
        UUID customer = accounts.createCustomer("Transfer concurrency").id();
        UUID account = accounts.openAccount(customer, currency).id();
        if (!balance.equals("0")) {
            deposits.deposit(UUID.randomUUID().toString(), account,
                    Money.parse(balance, SupportedCurrencies.require(currency)));
        }
        return account;
    }

    UUID quote(String from, String to, String amount) {
        return quotes.create(Money.parse(amount, SupportedCurrencies.require(from)), SupportedCurrencies.require(to)).id();
    }

    long balance(UUID account) {
        return accounts.findAccount(account).orElseThrow().balance().minorUnits();
    }

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
    void oneQuoteIsCarriedOutOnceHoweverManyRequestsRaceForIt() throws Exception {
        UUID sender = account("EUR", "100.00");
        UUID recipient = account("GBP", "0");
        UUID quote = quote("EUR", "GBP", "10.00");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                try {
                    // A different key each time: these are not retries, but
                    // separate attempts to spend one quote.
                    transfers.transfer(UUID.randomUUID().toString(), quote, sender, recipient);
                    succeeded.incrementAndGet();
                } catch (DomainException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.QUOTE_ALREADY_USED);
                    refused.incrementAndGet();
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        assertThat(succeeded).hasValue(1);
        assertThat(refused).hasValue(THREADS - 1);
        assertThat(balance(sender)).isEqualTo(9_000);
        // One quote's worth: 10.00 EUR less 0.05 fee = 9.95 x 0.85 = 8.4575 -> 8.45 GBP.
        assertThat(balance(recipient)).isEqualTo(845);
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void manyQuotesAgainstOneAccountNeverOverdrawIt() throws Exception {
        UUID sender = account("EUR", "100.00");
        UUID recipient = account("USD", "0");
        List<UUID> quoteIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            quoteIds.add(quote("EUR", "USD", "10.00"));
        }
        Set<UUID> done = ConcurrentHashMap.newKeySet();
        AtomicInteger refused = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (UUID quote : quoteIds) {
            tasks.add(() -> {
                try {
                    done.add(transfers.transfer(UUID.randomUUID().toString(), quote, sender, recipient).transfer().id());
                } catch (DomainException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    refused.incrementAndGet();
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        assertThat(done).hasSize(10);
        assertThat(refused).hasValue(THREADS - 10);
        assertThat(balance(sender)).isZero();
        LedgerInvariants.assertHold(jdbc);
    }

    @Test
    void opposingCrossCurrencyTransfersDoNotDeadlock() throws Exception {
        // EUR -> GBP and GBP -> EUR at once touch the same four accounts
        // (two customers, two FX positions) from opposite ends.
        UUID eur = account("EUR", "10000.00");
        UUID gbp = account("GBP", "10000.00");
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean forward = i % 2 == 0;
            tasks.add(() -> {
                for (int n = 0; n < 10; n++) {
                    if (forward) {
                        transfers.transfer(UUID.randomUUID().toString(), quote("EUR", "GBP", "1.00"), eur, gbp);
                    } else {
                        transfers.transfer(UUID.randomUUID().toString(), quote("GBP", "EUR", "1.00"), gbp, eur);
                    }
                }
            });
        }

        List<Throwable> failures = runTogether(tasks);

        assertThat(failures).isEmpty();
        LedgerInvariants.assertHold(jdbc);
    }
}
