-- System accounts (one EXTERNAL account per currency) are where money enters and leaves the
-- ledger, so deposits are double-entry too. They are the only accounts allowed to go negative.

ALTER TABLE accounts ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_balance_non_negative CHECK (is_system OR balance >= 0);

CREATE UNIQUE INDEX accounts_one_system_per_currency ON accounts (currency) WHERE is_system;
