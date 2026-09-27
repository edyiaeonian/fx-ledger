-- Idempotency keys were unique across the whole table, so two customers who
-- happened to pick the same key collided: the second got a 409 for a request
-- that had nothing to do with the first. A key is chosen by one client, and
-- with no authentication the account stands in for the client, so a key is
-- now unique per account: the account deposited into, or transferred from.

ALTER TABLE deposits DROP CONSTRAINT deposits_idempotency_key_key;
ALTER TABLE deposits ADD CONSTRAINT deposits_account_idempotency_key_unique
    UNIQUE (account_id, idempotency_key);

ALTER TABLE transfers DROP CONSTRAINT transfers_idempotency_key_key;
ALTER TABLE transfers ADD CONSTRAINT transfers_source_idempotency_key_unique
    UNIQUE (source_account_id, idempotency_key);
