package dev.edyiaeonian.fxledger.deposit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.account.Account;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.ledger.EntryType;
import dev.edyiaeonian.fxledger.ledger.LedgerService;
import dev.edyiaeonian.fxledger.ledger.LedgerService.PostingRequest;
import dev.edyiaeonian.fxledger.money.Money;

/**
 * Simulated deposits: money arriving from outside, recorded against the
 * currency's FUNDING account.
 */
@Service
public class DepositService {

    /** replayed is true when the deposit was made by an earlier request with the same key. */
    public record Result(Deposit deposit, boolean replayed) {}

    private final DepositRepository deposits;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final Clock clock;

    DepositService(DepositRepository deposits, AccountService accounts, LedgerService ledger, Clock clock) {
        this.deposits = deposits;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Deposits once per idempotency key.
     *
     * <p>Everything happens in one transaction. If any step fails, the claim
     * on the key rolls back with it, so a request that failed -- the wrong
     * currency, say -- can be corrected and retried under the same key. Only
     * a deposit that happened is remembered.
     */
    @Transactional
    public Result deposit(String idempotencyKey, UUID accountId, Money amount) {
        // Checked before the key is claimed: they depend only on the request,
        // and the deposit row's foreign key would refuse a bad one anyway.
        Account account = accounts.findAccount(accountId)
                .filter(a -> a.type() == AccountType.CUSTOMER)
                .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId));
        if (!account.balance().currency().equals(amount.currency())) {
            throw new DomainException(
                    ErrorCode.CURRENCY_MISMATCH,
                    "account " + accountId + " holds " + account.balance().currency() + ", not " + amount.currency());
        }

        String hash = requestHash(accountId, amount);
        Deposit deposit = new Deposit(
                UUID.randomUUID(), accountId, amount, UUID.randomUUID(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));

        if (!deposits.claim(idempotencyKey, hash, deposit)) {
            DepositRepository.Stored earlier = deposits.findByKey(idempotencyKey).orElseThrow();
            if (!earlier.requestHash().equals(hash)) {
                throw new DomainException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "idempotency key already used for a different deposit");
            }
            return new Result(earlier.deposit(), true);
        }

        UUID funding = accounts.systemAccountId(AccountType.FUNDING, amount.currency());
        ledger.post(deposit.entryId(), EntryType.DEPOSIT, List.of(
                new PostingRequest(funding, amount.negate()),
                new PostingRequest(accountId, amount)));
        return new Result(deposit, false);
    }

    // What makes two requests "the same": the account and the exact amount.
    private static String requestHash(UUID accountId, Money amount) {
        String canonical = accountId + "|" + amount.currency().getCurrencyCode() + "|" + amount.minorUnits();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException(impossible);
        }
    }
}
