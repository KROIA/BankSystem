CREATE TABLE IF NOT EXISTS SuspicionFlag (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    account_number INTEGER NOT NULL,
    flag_type INTEGER NOT NULL,
    severity INTEGER NOT NULL DEFAULT 0,
    amount INTEGER NOT NULL DEFAULT 0,
    ref TEXT,
    details TEXT,
    acknowledged INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_flag_ts ON SuspicionFlag (ts DESC);
