CREATE TABLE IF NOT EXISTS PayoutHistory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    schedule_id INTEGER NOT NULL,
    source_account INTEGER NOT NULL,
    target_uuid TEXT NOT NULL,
    amount INTEGER NOT NULL,
    ts INTEGER NOT NULL,
    status TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payout_history_company_ts ON PayoutHistory (company_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_payout_history_schedule_ts ON PayoutHistory (schedule_id, ts DESC);
