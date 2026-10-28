-- Amounts are stored in minor units (e.g. cents) as BIGINT. Never floating point.

CREATE TABLE accounts (
    id          UUID        PRIMARY KEY,
    currency    CHAR(3)     NOT NULL,
    balance     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transfers (
    id               UUID        PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    from_account_id  UUID        NOT NULL REFERENCES accounts (id),
    to_account_id    UUID        NOT NULL REFERENCES accounts (id),
    amount           BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT transfers_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT transfers_distinct_accounts CHECK (from_account_id <> to_account_id)
);

-- Append-only. Each transfer writes exactly two rows whose amounts sum to zero.
CREATE TABLE ledger_entries (
    id           BIGSERIAL   PRIMARY KEY,
    transfer_id  UUID        NOT NULL REFERENCES transfers (id),
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    amount       BIGINT      NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_entries_amount_nonzero CHECK (amount <> 0)
);

CREATE INDEX ledger_entries_account_id_idx ON ledger_entries (account_id, id);
CREATE INDEX ledger_entries_transfer_id_idx ON ledger_entries (transfer_id);
