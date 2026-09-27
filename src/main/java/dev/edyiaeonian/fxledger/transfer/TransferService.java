package dev.edyiaeonian.fxledger.transfer;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.account.Account;
import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.common.idempotency.IdempotencyKeys;
import dev.edyiaeonian.fxledger.fx.Quote;
import dev.edyiaeonian.fxledger.fx.QuoteService;
import dev.edyiaeonian.fxledger.ledger.EntryType;
import dev.edyiaeonian.fxledger.ledger.LedgerService;
import dev.edyiaeonian.fxledger.ledger.LedgerService.PostingRequest;
import dev.edyiaeonian.fxledger.money.Money;

/**
 * Carries out a quote: the one place that coordinates accounts, quotes and
 * the ledger.
 *
 * <p>Everything happens in one transaction, and locks are always taken in
 * the same order -- the quote, then the accounts (in id order, by the
 * ledger) -- so two transfers can wait for each other but never deadlock.
 */
@Service
public class TransferService {

    /** A transfer with the quote it carried out; replayed if an earlier request made it. */
    public record Result(Transfer transfer, Quote quote, boolean replayed) {}

    private final TransferRepository transfers;
    private final AccountService accounts;
    private final QuoteService quotes;
    private final LedgerService ledger;
    private final Clock clock;

    TransferService(
            TransferRepository transfers,
            AccountService accounts,
            QuoteService quotes,
            LedgerService ledger,
            Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.quotes = quotes;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public Result transfer(String idempotencyKey, UUID quoteId, UUID sourceAccountId, UUID targetAccountId) {
        // Checks that depend only on the request come before the key is
        // claimed; the transfer row's foreign keys would refuse these anyway.
        if (sourceAccountId.equals(targetAccountId)) {
            throw new DomainException(ErrorCode.SAME_ACCOUNT, "an account cannot transfer to itself");
        }
        Account source = customerAccount(sourceAccountId);
        Account target = customerAccount(targetAccountId);
        quotes.find(quoteId);

        String hash = IdempotencyKeys.requestHash(quoteId, sourceAccountId, targetAccountId);
        Transfer transfer = new Transfer(
                UUID.randomUUID(), quoteId, sourceAccountId, targetAccountId, UUID.randomUUID(), "COMPLETED",
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        boolean claimed;
        try {
            claimed = transfers.claim(idempotencyKey, hash, transfer);
        } catch (DuplicateKeyException quoteTaken) {
            // Another transfer, under another key, carried out this quote.
            throw new DomainException(ErrorCode.QUOTE_ALREADY_USED, "quote " + quoteId + " has already been used");
        }
        if (!claimed) {
            TransferRepository.Stored earlier = transfers.findByKey(sourceAccountId, idempotencyKey).orElseThrow();
            if (!earlier.requestHash().equals(hash)) {
                throw new DomainException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED, "idempotency key already used for a different transfer");
            }
            return new Result(earlier.transfer(), quotes.find(quoteId), true);
        }

        Quote quote = quotes.use(quoteId, source.balance().currency(), target.balance().currency());
        ledger.post(transfer.entryId(), EntryType.TRANSFER, postings(quote, sourceAccountId, targetAccountId));
        return new Result(transfer, quote, false);
    }

    @Transactional(readOnly = true)
    public Result find(UUID id) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.TRANSFER_NOT_FOUND, "no transfer " + id));
        return new Result(transfer, quotes.find(transfer.quoteId()), false);
    }

    /**
     * The entry for a quote. Across currencies, the FX_POSITION accounts take
     * the source money in and pay the target money out, so each currency
     * balances on its own:
     *
     * <pre>
     *   sender         -source        (source currency)
     *   fee revenue    +fee           (source currency, if any)
     *   FX position    +source-fee    (source currency)
     *   FX position    -target        (target currency)
     *   recipient      +target        (target currency)
     * </pre>
     *
     * In one currency the FX lines are not needed: the recipient gets
     * source - fee directly.
     */
    private List<PostingRequest> postings(Quote quote, UUID sender, UUID recipient) {
        Currency from = quote.source().currency();
        Currency to = quote.target().currency();
        List<PostingRequest> lines = new ArrayList<>();
        lines.add(new PostingRequest(sender, quote.source().negate()));
        if (!quote.fee().isZero()) {
            lines.add(new PostingRequest(accounts.systemAccountId(AccountType.FEE_REVENUE, from), quote.fee()));
        }
        if (from.equals(to)) {
            lines.add(new PostingRequest(recipient, quote.target()));
        } else {
            Money converted = quote.source().minus(quote.fee());
            lines.add(new PostingRequest(accounts.systemAccountId(AccountType.FX_POSITION, from), converted));
            lines.add(new PostingRequest(accounts.systemAccountId(AccountType.FX_POSITION, to), quote.target().negate()));
            lines.add(new PostingRequest(recipient, quote.target()));
        }
        return lines;
    }

    private Account customerAccount(UUID id) {
        return accounts.findAccount(id)
                .filter(account -> account.type() == AccountType.CUSTOMER)
                .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + id));
    }
}
