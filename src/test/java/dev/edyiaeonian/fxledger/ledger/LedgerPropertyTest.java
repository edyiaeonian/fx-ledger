package dev.edyiaeonian.fxledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.edyiaeonian.fxledger.TestcontainersConfiguration;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.deposit.DepositService;
import dev.edyiaeonian.fxledger.ledger.LedgerService.PostingRequest;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Random sequences of operations, checked against a model simple enough to be
 * obviously right: a map from account to the balance it should have.
 *
 * <p>After every step, each balance must match the model and every ledger
 * invariant must hold. When a sequence fails, jqwik shrinks it to the
 * shortest one that still fails and prints it, with the seed to replay it.
 */
@JqwikSpringSupport
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPropertyTest {

    sealed interface Op permits Open, Deposit, Retry, Move {}

    /** Opens an account in this currency, for a new customer. */
    record Open(String currency) implements Op {}

    /** Deposits into the n-th account (modulo the number open). */
    record Deposit(int account, long amount) implements Op {}

    /** Repeats the n-th earlier deposit with its original idempotency key. */
    record Retry(int deposit) implements Op {}

    /** Moves money between two accounts of the same currency; may overdraw. */
    record Move(int from, int to, long amount) implements Op {}

    record DepositCall(String key, UUID account, Money amount) {}

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

    @Property(tries = 200)
    void theLedgerAlwaysMatchesTheModel(@ForAll("operations") List<Op> operations) {
        List<UUID> open = new ArrayList<>();
        Map<UUID, Currency> currencyOf = new HashMap<>();
        Map<UUID, Long> expected = new HashMap<>();
        List<DepositCall> made = new ArrayList<>();

        for (Op op : operations) {
            switch (op) {
                case Open(String currency) -> {
                    UUID customer = accounts.createCustomer("property").id();
                    UUID account = accounts.openAccount(customer, currency).id();
                    open.add(account);
                    currencyOf.put(account, Currency.getInstance(currency));
                    expected.put(account, 0L);
                }
                case Deposit(int index, long amount) when !open.isEmpty() -> {
                    UUID account = open.get(index % open.size());
                    DepositCall call = new DepositCall(
                            UUID.randomUUID().toString(), account, Money.ofMinor(amount, currencyOf.get(account)));
                    assertThat(deposits.deposit(call.key(), call.account(), call.amount()).replayed()).isFalse();
                    made.add(call);
                    expected.merge(account, amount, Long::sum);
                }
                case Retry(int index) when !made.isEmpty() -> {
                    DepositCall call = made.get(index % made.size());
                    // Same key, same request: the original result, and no money moves.
                    assertThat(deposits.deposit(call.key(), call.account(), call.amount()).replayed()).isTrue();
                }
                case Move(int fromIndex, int toIndex, long amount) when !open.isEmpty() -> {
                    UUID from = open.get(fromIndex % open.size());
                    List<UUID> sameCurrency = open.stream()
                            .filter(a -> !a.equals(from) && currencyOf.get(a).equals(currencyOf.get(from)))
                            .toList();
                    if (sameCurrency.isEmpty()) {
                        continue;
                    }
                    UUID to = sameCurrency.get(toIndex % sameCurrency.size());
                    Currency currency = currencyOf.get(from);
                    Runnable move = () -> transaction.executeWithoutResult(status -> ledger.post(
                            UUID.randomUUID(),
                            EntryType.TRANSFER,
                            List.of(
                                    new PostingRequest(from, Money.ofMinor(-amount, currency)),
                                    new PostingRequest(to, Money.ofMinor(amount, currency)))));
                    if (expected.get(from) < amount) {
                        assertThatThrownBy(move::run)
                                .isInstanceOf(DomainException.class)
                                .extracting(e -> ((DomainException) e).code())
                                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    } else {
                        move.run();
                        expected.merge(from, -amount, Long::sum);
                        expected.merge(to, amount, Long::sum);
                    }
                }
                default -> {
                    // A deposit, retry or move before there is anything to act on.
                }
            }

            for (UUID account : open) {
                long actual = accounts.findAccount(account).orElseThrow().balance().minorUnits();
                assertThat(actual).as("balance of %s after %s", account, op).isEqualTo(expected.get(account));
            }
            LedgerInvariants.assertHold(jdbc);
        }
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Integer> index = Arbitraries.integers().between(0, 20);
        Arbitrary<Op> open = Arbitraries.of("EUR", "GBP", "JPY").map(Open::new);
        Arbitrary<Op> deposit = Combinators.combine(index, Arbitraries.longs().between(1, 100_000))
                .as(Deposit::new);
        Arbitrary<Op> retry = index.map(Retry::new);
        // Moves go up to more than a deposit brings in, so overdrafts are tried often.
        Arbitrary<Op> move = Combinators.combine(index, index, Arbitraries.longs().between(1, 150_000))
                .as(Move::new);
        return Arbitraries.frequencyOf(
                        Tuple.of(2, open), Tuple.of(4, deposit), Tuple.of(1, retry), Tuple.of(4, move))
                .list()
                .ofMinSize(1)
                .ofMaxSize(30);
    }
}
