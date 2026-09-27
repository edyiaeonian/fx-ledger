-- Reference rates as published, and quotes priced from them.

-- One row per currency per ECB publication day, each rate being how many
-- units of the currency one euro buys. Kept, not overwritten, so any quote
-- can be traced to the rates it was priced from.
CREATE TABLE fx_rates (
    rate_date  DATE        NOT NULL,
    currency   CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$' AND currency <> 'EUR'),
    -- NUMERIC with no declared scale stores the published digits exactly.
    rate       NUMERIC     NOT NULL CHECK (rate > 0),
    fetched_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (rate_date, currency)
);

CREATE TABLE quotes (
    id              UUID        PRIMARY KEY,
    source_currency CHAR(3)     NOT NULL,
    target_currency CHAR(3)     NOT NULL,
    -- Minor units of the source currency, like every amount.
    source_amount   BIGINT      NOT NULL CHECK (source_amount > 0),
    fee             BIGINT      NOT NULL CHECK (fee >= 0),
    -- Units of the target currency per unit of the source, rounded to ten
    -- decimals. The target amount is computed from exactly this number.
    rate            NUMERIC     NOT NULL CHECK (rate > 0),
    target_amount   BIGINT      NOT NULL CHECK (target_amount > 0),
    rate_date       DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('OPEN', 'USED')),

    CHECK (fee < source_amount),
    CHECK (expires_at > created_at)
);
