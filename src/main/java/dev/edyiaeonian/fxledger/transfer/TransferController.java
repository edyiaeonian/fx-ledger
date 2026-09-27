package dev.edyiaeonian.fxledger.transfer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.edyiaeonian.fxledger.common.idempotency.IdempotencyKeys;
import dev.edyiaeonian.fxledger.fx.Quote;

@RestController
@Tag(name = "4. Transfers")
class TransferController {

    record TransferRequest(@NotNull UUID quoteId, @NotNull UUID sourceAccountId, @NotNull UUID targetAccountId) {}

    record TransferResponse(
            UUID id,
            UUID quoteId,
            UUID sourceAccountId,
            UUID targetAccountId,
            String sourceAmount,
            String sourceCurrency,
            String fee,
            String rate,
            String targetAmount,
            String targetCurrency,
            UUID entryId,
            String status,
            Instant createdAt) {

        static TransferResponse of(TransferService.Result result) {
            Transfer transfer = result.transfer();
            Quote quote = result.quote();
            return new TransferResponse(
                    transfer.id(),
                    transfer.quoteId(),
                    transfer.sourceAccountId(),
                    transfer.targetAccountId(),
                    quote.source().toDecimalString(),
                    quote.source().currency().getCurrencyCode(),
                    quote.fee().toDecimalString(),
                    quote.rate().toPlainString(),
                    quote.target().toDecimalString(),
                    quote.target().currency().getCurrencyCode(),
                    transfer.entryId(),
                    transfer.status(),
                    transfer.createdAt());
        }
    }

    private final TransferService service;

    TransferController(TransferService service) {
        this.service = service;
    }

    @Operation(
            summary = "Carry out a quote between two accounts",
            description = "Moves exactly what the quote says, once. Idempotent by key. Errors: QUOTE_NOT_FOUND, QUOTE_ALREADY_USED, QUOTE_EXPIRED, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS, SAME_ACCOUNT, ACCOUNT_NOT_FOUND, IDEMPOTENCY_KEY_REUSED.")
    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "Chosen by the client, unique per operation; a retry with the same key and body returns the original result.", example = "3f2b8c1e-5d7a-4e2b-9c1f-0a6d4e8b7c21")
                    @RequestHeader(name = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        TransferService.Result result =
                service.transfer(key, request.quoteId(), request.sourceAccountId(), request.targetAccountId());
        var response = ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/transfers/" + result.transfer().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(TransferResponse.of(result));
    }

    @Operation(summary = "Read a transfer")
    @GetMapping("/transfers/{id}")
    TransferResponse find(@PathVariable UUID id) {
        return TransferResponse.of(service.find(id));
    }
}
