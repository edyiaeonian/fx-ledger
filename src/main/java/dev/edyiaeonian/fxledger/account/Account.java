package dev.edyiaeonian.fxledger.account;

import java.time.Instant;
import java.util.UUID;

import dev.edyiaeonian.fxledger.money.Money;

/** customerId is null for a system account. */
public record Account(UUID id, UUID customerId, AccountType type, Money balance, Instant createdAt) {}
