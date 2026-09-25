package dev.edyiaeonian.fxledger.deposit;

import java.time.Instant;
import java.util.UUID;

import dev.edyiaeonian.fxledger.money.Money;

public record Deposit(UUID id, UUID accountId, Money amount, UUID entryId, Instant createdAt) {}
