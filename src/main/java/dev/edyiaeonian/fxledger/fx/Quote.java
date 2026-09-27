package dev.edyiaeonian.fxledger.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import dev.edyiaeonian.fxledger.money.Money;

/**
 * A price, fixed for a limited time: send {@code source}, pay {@code fee} out
 * of it, and the recipient gets {@code target}.
 */
public record Quote(
        UUID id,
        Money source,
        Money fee,
        BigDecimal rate,
        Money target,
        LocalDate rateDate,
        Instant createdAt,
        Instant expiresAt,
        QuoteStatus status) {

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
