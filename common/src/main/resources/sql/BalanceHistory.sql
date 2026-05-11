CREATE TABLE IF NOT EXISTS BalanceHistory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_number INTEGER NOT NULL,
    item_id INTEGER NOT NULL,
    balance INTEGER NOT NULL,
    locked_balance INTEGER NOT NULL,
    time INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_balance_history ON BalanceHistory (account_number, item_id, time);
