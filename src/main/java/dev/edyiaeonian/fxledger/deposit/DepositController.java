package dev.edyiaeonian.fxledger.deposit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

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
import dev.edyiaeonian.fxledger.common.idempotency.IdempotencyKeys;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;

@RestController
@Tag(name = "2. Deposits")
class DepositController {

    record DepositRequest(
            @Schema(example = "250.00") @NotNull String amount, @Schema(example = "EUR") @NotNull String currency) {}

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

    @Operation(
            summary = "Deposit into an account (simulated incoming money)",
            description = "Idempotent by key. Errors: INVALID_AMOUNT, IDEMPOTENCY_KEY_REQUIRED, ACCOUNT_NOT_FOUND, CURRENCY_MISMATCH, IDEMPOTENCY_KEY_REUSED.")
    @PostMapping("/accounts/{accountId}/deposits")
    ResponseEntity<DepositResponse> deposit(
            @PathVariable UUID accountId,
            @Parameter(description = "Chosen by the client, unique per operation; a retry with the same key and body returns the original result.", example = "3f2b8c1e-5d7a-4e2b-9c1f-0a6d4e8b7c21")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        Money amount = parsePositive(request.amount(), request.currency());

        DepositService.Result result = service.deposit(key, accountId, amount);

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
