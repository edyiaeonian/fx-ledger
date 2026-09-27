package dev.edyiaeonian.fxledger.fx;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;
import dev.edyiaeonian.fxledger.money.SupportedCurrencies;

@RestController
class QuoteController {

    record QuoteRequest(@NotNull String sourceCurrency, @NotNull String targetCurrency, @NotNull String sourceAmount) {}

    record QuoteResponse(
            UUID id,
            String sourceAmount,
            String sourceCurrency,
            String fee,
            String rate,
            String targetAmount,
            String targetCurrency,
            LocalDate rateDate,
            Instant createdAt,
            Instant expiresAt,
            String status,
            boolean expired) {

        static QuoteResponse of(Quote quote, boolean expired) {
            return new QuoteResponse(
                    quote.id(),
                    quote.source().toDecimalString(),
                    quote.source().currency().getCurrencyCode(),
                    quote.fee().toDecimalString(),
                    quote.rate().toPlainString(),
                    quote.target().toDecimalString(),
                    quote.target().currency().getCurrencyCode(),
                    quote.rateDate(),
                    quote.createdAt(),
                    quote.expiresAt(),
                    quote.status().name(),
                    expired);
        }
    }

    private final QuoteService service;

    QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping("/quotes")
    ResponseEntity<QuoteResponse> create(@Valid @RequestBody QuoteRequest request) {
        Currency source = SupportedCurrencies.require(request.sourceCurrency());
        Currency target = SupportedCurrencies.require(request.targetCurrency());
        Money amount;
        try {
            amount = Money.parse(request.sourceAmount(), source);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, invalid.getMessage());
        }
        if (amount.isZero() || amount.isNegative()) {
            throw new DomainException(ErrorCode.INVALID_AMOUNT, "the amount must be positive");
        }
        Quote quote = service.create(amount, target);
        return ResponseEntity.created(URI.create("/quotes/" + quote.id()))
                .body(QuoteResponse.of(quote, false));
    }

    @GetMapping("/quotes/{id}")
    QuoteResponse find(@PathVariable UUID id) {
        Quote quote = service.find(id);
        return QuoteResponse.of(quote, service.isExpired(quote));
    }
}
