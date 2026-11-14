CREATE TABLE IF NOT EXISTS TransactionLog (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_number INTEGER NOT NULL,
    actor_uuid TEXT,
    kind TEXT NOT NULL,
    item_id INTEGER NOT NULL,
    amount INTEGER NOT NULL,
    other_account INTEGER,
    company_id INTEGER,
    ts INTEGER NOT NULL,
    note TEXT,
    source_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
    tag TEXT,
    type_code INTEGER NOT NULL DEFAULT 0,
    ref TEXT,
    money_amount INTEGER,
    price INTEGER,
    currency_item INTEGER,
    actor_name TEXT,
    balance_after INTEGER,
    virtual_qty INTEGER,
    origin_server TEXT,
    dimension TEXT,
    x INTEGER,
    y INTEGER,
    z INTEGER
);

CREATE INDEX IF NOT EXISTS idx_tx_log_account_ts ON TransactionLog (account_number, ts DESC);
CREATE INDEX IF NOT EXISTS idx_tx_log_company_ts ON TransactionLog (company_id, ts DESC);
