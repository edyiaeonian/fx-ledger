package dev.edyiaeonian.fxledger.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.account.Account;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;

/**
 * The double-entry ledger: the only code that moves money between accounts.
 *
 * <p>Every entry's postings sum to zero in each currency, so money is never
 * created or destroyed, only moved. Postings are only ever added.
 */
@Service
public class LedgerService {

    /** One line of an entry: a signed amount for one account. */
    public record PostingRequest(UUID accountId, Money amount) {}

    private final AccountService accounts;
    private final LedgerRepository repository;
    private final Clock clock;
    private final Duration lockTimeout;

    LedgerService(
            AccountService accounts,
            LedgerRepository repository,
            Clock clock,
            @Value("${fxledger.ledger.lock-timeout:5s}") Duration lockTimeout) {
        this.accounts = accounts;
        this.repository = repository;
        this.clock = clock;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Records one balanced entry and updates the balances it touches.
     *
     * <p>Must run inside the caller's transaction (MANDATORY), so that the
     * caller's own writes -- an idempotency record, a quote marked as used --
     * commit or roll back together with the entry.
     *
     * @throws IllegalArgumentException if the entry is empty, has a zero
     *     posting, or does not balance in every currency: a bug in the caller
     * @throws DomainException ACCOUNT_NOT_FOUND, CURRENCY_MISMATCH, or
     *     INSUFFICIENT_FUNDS
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID entryId, EntryType type, List<PostingRequest> postings) {
        requireBalanced(postings);

        // Waiting for another transaction's lock is capped, so a stuck request
        // fails with a retryable error instead of hanging.
        repository.setLockTimeout(lockTimeout);
        Map<UUID, Account> locked =
                accounts.lockInIdOrder(postings.stream().map(PostingRequest::accountId).toList());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        repository.insertEntry(entryId, type, now);
        for (PostingRequest posting : postings) {
            Account account = locked.get(posting.accountId());
            if (!account.balance().currency().equals(posting.amount().currency())) {
                throw new DomainException(
                        ErrorCode.CURRENCY_MISMATCH,
                        "account " + account.id() + " holds " + account.balance().currency()
                                + ", not " + posting.amount().currency());
            }
            Money after = account.balance().plus(posting.amount());
            if (account.type() == AccountType.CUSTOMER && after.isNegative()) {
                throw new DomainException(
                        ErrorCode.INSUFFICIENT_FUNDS,
                        "account " + account.id() + " has " + account.balance()
                                + ", which does not cover " + posting.amount().negate());
            }
            repository.insertPosting(entryId, account.id(), posting.amount(), after, now);
            accounts.updateBalance(account.id(), after);
            // The same account may appear twice in one entry; the next line
            // must start from this one's result.
            locked.put(account.id(), new Account(
                    account.id(), account.customerId(), account.type(), after, account.createdAt()));
        }
        return entryId;
    }

    private static void requireBalanced(List<PostingRequest> postings) {
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("an entry needs at least one posting");
        }
        if (postings.stream().anyMatch(p -> p.amount().isZero())) {
            throw new IllegalArgumentException("a posting cannot be zero");
        }
        Map<Currency, Long> totals = postings.stream()
                .collect(Collectors.groupingBy(
                        p -> p.amount().currency(),
                        Collectors.reducing(0L, p -> p.amount().minorUnits(), Math::addExact)));
        totals.forEach((currency, total) -> {
            if (total != 0) {
                throw new IllegalArgumentException(
                        "entry does not balance: " + currency + " postings sum to " + total);
            }
        });
    }
}
