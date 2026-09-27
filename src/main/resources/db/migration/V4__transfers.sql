-- Transfers: a quote carried out between two customer accounts.
CREATE TABLE transfers (
    id                UUID        PRIMARY KEY,
    idempotency_key   TEXT        NOT NULL UNIQUE,
    request_hash      TEXT        NOT NULL,
    -- UNIQUE: a quote is carried out at most once. The service checks this
    -- under a lock on the quote; the constraint holds even if that check
    -- were ever skipped.
    quote_id          UUID        NOT NULL UNIQUE REFERENCES quotes (id),
    source_account_id UUID        NOT NULL REFERENCES accounts (id),
    target_account_id UUID        NOT NULL REFERENCES accounts (id),
    -- Deferred, as for deposits: the transfer row claims its key before the
    -- journal entry it names exists.
    entry_id          UUID        NOT NULL REFERENCES journal_entries (id) DEFERRABLE INITIALLY DEFERRED,
    -- Only completed transfers are ever stored: a failure rolls back with its
    -- transaction. The column leaves room for asynchronous states later.
    status            TEXT        NOT NULL CHECK (status IN ('COMPLETED')),
    created_at        TIMESTAMPTZ NOT NULL,

    CHECK (source_account_id <> target_account_id)
);
