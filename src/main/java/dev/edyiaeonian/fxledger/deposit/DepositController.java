package dev.edyiaeonian.fxledger.deposit;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;

@RestController
class DepositController {

    // Printable ASCII, 1 to 255 characters: room for a UUID or any client's
    // own scheme, nothing that could smuggle control characters into logs.
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    record DepositRequest(@NotNull String amount, @NotNull String currency) {}

    record DepositResponse(UUID id, UUID accountId, String amount, String currency, UUID entryId, Instant createdAt) {
        static DepositResponse of(Deposit deposit) {
            return new DepositResponse(
                    deposit.id(),
                    deposit.accountId(),
                    deposit.amount().toDecimalString(),
                    deposit.amount().currency().getCurrencyCode(),
                    deposit.entryId(),
                    deposit.createdAt());
        }
    }

    private final DepositService service;

    DepositController(DepositService service) {
        this.service = service;
    }

    @PostMapping("/accounts/{accountId}/deposits")
    ResponseEntity<DepositResponse> deposit(
            @PathVariable UUID accountId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "an Idempotency-Key header of 1 to 255 printable ASCII characters is required");
        }
        Money amount = parsePositive(request.amount(), request.currency());

        DepositService.Result result = service.deposit(idempotencyKey, accountId, amount);

        // A replay answers exactly as the original did, and says so.
        var response = ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/accounts/" + accountId + "/deposits/" + result.deposit().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(DepositResponse.of(result.deposit()));
    }

    private static Money parsePositive(String amount, String currencyCode) {
        Money money;
        try {
            money = Money.parse(amount, SupportedCurrencies.require(currencyCode));
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, invalid.getMessage());
        }
        if (money.isNegative() || money.isZero()) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, "a deposit must be positive, got " + amount);
        }
        return money;
    }
}
