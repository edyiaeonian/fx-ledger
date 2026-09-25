package dev.edyiaeonian.fxledger.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.edyiaeonian.fxledger.account.AccountService;
import dev.edyiaeonian.fxledger.account.AccountType;
import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.ledger.LedgerRepository.StatementLine;

@RestController
@Validated
class StatementController {

    record StatementItem(UUID entryId, String type, String amount, String balanceAfter, Instant createdAt) {
        static StatementItem of(StatementLine line) {
            return new StatementItem(
                    line.entryId(),
                    line.type().name(),
                    line.amount().toDecimalString(),
                    line.balanceAfter().toDecimalString(),
                    line.createdAt());
        }
    }

    /** nextCursor is null on the last page. */
    record StatementPage(String currency, List<StatementItem> items, String nextCursor) {}

    private final AccountService accounts;
    private final LedgerRepository ledger;

    StatementController(AccountService accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @GetMapping("/accounts/{accountId}/statement")
    @Transactional(readOnly = true)
    StatementPage statement(
            @PathVariable UUID accountId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        var account = accounts.findAccount(accountId)
                .filter(a -> a.type() == AccountType.CUSTOMER)
                .orElseThrow(() -> new DomainException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId));

        // One extra row tells whether another page follows.
        List<StatementLine> lines = ledger.statement(accountId, decode(cursor), limit + 1);
        boolean more = lines.size() > limit;
        List<StatementLine> page = more ? lines.subList(0, limit) : lines;
        String next = more ? encode(page.getLast().postingId()) : null;
        return new StatementPage(
                account.balance().currency().getCurrencyCode(),
                page.stream().map(StatementItem::of).toList(),
                next);
    }

    // The cursor is opaque to clients: they pass back what they were given.
    private static String encode(long postingId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("p" + postingId).getBytes(StandardCharsets.US_ASCII));
    }

    private static Long decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!text.startsWith("p")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(text.substring(1));
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.MALFORMED_REQUEST, "invalid cursor");
        }
    }
}
