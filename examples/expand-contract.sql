-- Additive migration reference for a disposable PostgreSQL database.
-- Execute the phases separately; no production database is targeted by verify.ps1.

-- 1. Legacy schema. The actor and amount describe synthetic quotes, not payments.
CREATE SCHEMA IF NOT EXISTS quotes;
CREATE TABLE IF NOT EXISTS quotes.quote (
    id uuid PRIMARY KEY,
    fee_minor bigint NOT NULL CHECK (fee_minor >= 0),
    currency text NOT NULL CHECK (currency IN ('USD', 'EUR'))
);

-- 2. Expand. Legacy writers/readers remain valid; modern writers fill both columns.
ALTER TABLE quotes.quote ADD COLUMN IF NOT EXISTS fee_minor_v2 bigint;
ALTER TABLE quotes.quote ADD CONSTRAINT fee_v2_nonnegative
    CHECK (fee_minor_v2 IS NULL OR fee_minor_v2 >= 0) NOT VALID;
ALTER TABLE quotes.quote VALIDATE CONSTRAINT fee_v2_nonnegative;

-- 3. Backfill. For a large table, batch by PK and measure lock/replication pressure.
UPDATE quotes.quote SET fee_minor_v2 = fee_minor WHERE fee_minor_v2 IS NULL;
-- Modern readers during the transition: SELECT COALESCE(fee_minor_v2, fee_minor).

-- 4. After ALL legacy writers are retired, repeat the backfill, then validate presence.
-- These statements are commented because step 4 has an external rollout precondition.
-- UPDATE quotes.quote SET fee_minor_v2 = fee_minor WHERE fee_minor_v2 IS NULL;
-- ALTER TABLE quotes.quote ADD CONSTRAINT fee_v2_present CHECK (fee_minor_v2 IS NOT NULL) NOT VALID;
-- ALTER TABLE quotes.quote VALIDATE CONSTRAINT fee_v2_present;
-- ALTER TABLE quotes.quote ALTER COLUMN fee_minor_v2 SET NOT NULL;

-- 5. Contract only after old readers are retired and rollback no longer needs fee_minor.
-- This migration intentionally leaves both columns available and drops no data.
