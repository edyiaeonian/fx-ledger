package dev.edyiaeonian.fxledger.account;

import java.time.Instant;
import java.util.UUID;

public record Customer(UUID id, String name, Instant createdAt) {}
