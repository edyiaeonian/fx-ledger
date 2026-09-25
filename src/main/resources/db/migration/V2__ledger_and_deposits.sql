-- Double-entry ledger, system accounts and deposits.

-- Lets a posting reference (account, currency) together, so the database
-- itself guarantees a posting is in its account's currency.
ALTER TABLE accounts ADD CONSTRAINT accounts_id_currency_unique UNIQUE (id, currency);

-- One system account per type and currency.
CREATE UNIQUE INDEX accounts_one_system_account_per_type_currency
    ON accounts (type, currency) WHERE type <> 'CUSTOMER';

-- One business event: a deposit, a transfer.
CREATE TABLE journal_entries (
    id         UUID        PRIMARY KEY,
    type       TEXT        NOT NULL CHECK (type IN ('DEPOSIT', 'TRANSFER')),
    created_at TIMESTAMPTZ NOT NULL
);

-- The lines of an entry. Append-only: nothing updates or deletes a posting;
-- a correction is a new, reversing entry. Within an entry, the postings of
-- each currency sum to zero (checked by the application, and verified by the
-- invariant tests).
CREATE TABLE postings (
    -- Internal only, never exposed: gives postings a stable insertion order
    -- for statements and pagination.
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id      UUID        NOT NULL REFERENCES journal_entries (id),
    account_id    UUID        NOT NULL,
    currency      CHAR(3)     NOT NULL,
    amount        BIGINT      NOT NULL CHECK (amount <> 0),
    -- The account's balance right after this posting: a running balance for
    -- statements, and a check on the cached balance in accounts.
    balance_after BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,

    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX postings_by_account ON postings (account_id, id);
CREATE INDEX postings_by_entry ON postings (entry_id);

CREATE TABLE deposits (
    id              UUID        PRIMARY KEY,
    -- Chosen by the client; a retry with the same key must not deposit twice.
    idempotency_key TEXT        NOT NULL UNIQUE,
    -- SHA-256 of the request, to tell a retry from a different request that
    -- reuses the key.
    request_hash    TEXT        NOT NULL,
    account_id      UUID        NOT NULL,
    currency        CHAR(3)     NOT NULL,
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    -- Deferred: the deposit row is written first, to claim the key, and the
    -- journal entry it names is written later in the same transaction.
    entry_id        UUID        NOT NULL REFERENCES journal_entries (id) DEFERRABLE INITIALLY DEFERRED,
    created_at      TIMESTAMPTZ NOT NULL,

    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

-- The system accounts, one of each type per supported currency. The currency
-- list must match SupportedCurrencies.java; a test checks that it does.
INSERT INTO accounts (id, customer_id, currency, type, balance, created_at)
SELECT gen_random_uuid(), NULL, currency, type, 0, now()
FROM unnest(ARRAY[
        'AUD', 'BRL', 'CAD', 'CHF', 'CNY', 'CZK', 'DKK', 'EUR', 'GBP', 'HKD',
        'HUF', 'IDR', 'ILS', 'INR', 'ISK', 'JPY', 'KRW', 'MXN', 'MYR', 'NOK',
        'NZD', 'PHP', 'PLN', 'RON', 'SEK', 'SGD', 'THB', 'TRY', 'USD', 'ZAR'
    ]) AS currency
CROSS JOIN unnest(ARRAY['FEE_REVENUE', 'FX_POSITION', 'FUNDING']) AS type;
