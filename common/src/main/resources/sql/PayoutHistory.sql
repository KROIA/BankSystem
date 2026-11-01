CREATE TABLE IF NOT EXISTS PayoutHistory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    schedule_id INTEGER NOT NULL,
    source_account INTEGER NOT NULL,
    target_uuid TEXT NOT NULL,
    amount INTEGER NOT NULL,
    ts INTEGER NOT NULL,
    status TEXT NOT NULL,
    target_player_name TEXT NOT NULL DEFAULT '',
    target_account_name TEXT NOT NULL DEFAULT '',
    currency_item INTEGER NOT NULL DEFAULT 0,
    type TEXT NOT NULL DEFAULT 'NORMAL'
);

CREATE INDEX IF NOT EXISTS idx_payout_history_company_ts ON PayoutHistory (company_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_payout_history_schedule_ts ON PayoutHistory (schedule_id, ts DESC);
