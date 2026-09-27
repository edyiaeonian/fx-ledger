package dev.edyiaeonian.fxledger.transfer;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id, UUID quoteId, UUID sourceAccountId, UUID targetAccountId, UUID entryId, String status, Instant createdAt) {}
