-- =====================================================================
--  CUSTOMER MERGE LOG                                        (2026-09-14)
--
--  pgAdmin -> Query Tool on the "pawnbroking" database -> F5. Safe to run
--  again; it only creates what is missing.
--
--  Customer Details -> All Customer Details -> Find Duplicates merges a
--  customer typed more than once. Before it changes any bill it writes the
--  bill's old customer details here, so a merge can be undone ("Undo Last
--  Merge"). Until this table exists the app lists duplicates but will not
--  merge them.
-- =====================================================================

CREATE TABLE IF NOT EXISTS customer_merge_log (
    id                  BIGSERIAL PRIMARY KEY,
    merge_id            VARCHAR(40)  NOT NULL,           -- one per merge
    merged_at           TIMESTAMP    NOT NULL DEFAULT now(),
    merged_by           VARCHAR(100),
    table_name          VARCHAR(60)  NOT NULL,           -- company_billing / company_billing_suspense
    company_id          VARCHAR(100) NOT NULL,
    jewel_material_type VARCHAR(20)  NOT NULL,
    bill_number         VARCHAR(100) NOT NULL,
    row_ref             VARCHAR(100),                    -- the row itself, where the table has its own id
    old_values          JSONB        NOT NULL,           -- the customer details before the merge
    new_values          JSONB        NOT NULL,           -- what the merge wrote
    undone_at           TIMESTAMP                        -- set by Undo Last Merge
);

CREATE INDEX IF NOT EXISTS ix_customer_merge_log_merge ON customer_merge_log (merge_id);
CREATE INDEX IF NOT EXISTS ix_customer_merge_log_open  ON customer_merge_log (merged_at) WHERE undone_at IS NULL;

SELECT 'customer_merge_log' AS table_name,
       CASE WHEN to_regclass('public.customer_merge_log') IS NOT NULL THEN 'ok' ELSE 'MISSING' END AS status;
