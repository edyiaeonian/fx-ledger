package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.edyiaeonian.fxledger.common.error.DomainException;
import dev.edyiaeonian.fxledger.common.error.ErrorCode;
import dev.edyiaeonian.fxledger.money.Money;

@Service
public class QuoteService {

    private final FxRateService rates;
    private final QuoteRepository quotes;
    private final FxProperties properties;
    private final Clock clock;

    QuoteService(FxRateService rates, QuoteRepository quotes, FxProperties properties, Clock clock) {
        this.rates = rates;
        this.quotes = quotes;
        this.properties = properties;
        this.clock = clock;
    }

    /** Prices {@code source} in {@code target} at the current mid-market rate, plus the fee. */
    @Transactional
    public Quote create(Money source, Currency target) {
        RateSnapshot current = rates.current();
        BigDecimal rate = QuoteCalculator.rate(current, source.currency(), target);
        QuoteCalculator.Pricing pricing = QuoteCalculator.price(source, target, rate, properties.feeRate());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Quote quote = new Quote(
                UUID.randomUUID(),
                source,
                pricing.fee(),
                rate,
                pricing.target(),
                current.date(),
                now,
                now.plus(properties.quoteTtl()),
                QuoteStatus.OPEN);
        quotes.insert(quote);
        return quote;
    }

    @Transactional(readOnly = true)
    public Quote find(UUID id) {
        return quotes.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.QUOTE_NOT_FOUND, "no quote " + id));
    }

    public boolean isExpired(Quote quote) {
        return quote.isExpiredAt(clock.instant());
    }
}
