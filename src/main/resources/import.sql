-- Schema migrations for dev/test (H2). PostgreSQL prod uses Hibernate update strategy.
ALTER TABLE scalping_trade ADD COLUMN IF NOT EXISTS tp1hit BOOLEAN DEFAULT FALSE;
ALTER TABLE scalping_trade ADD COLUMN IF NOT EXISTS tp1pnl DOUBLE DEFAULT 0.0;
