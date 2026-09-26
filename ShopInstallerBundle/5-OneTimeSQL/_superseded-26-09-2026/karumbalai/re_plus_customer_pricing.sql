-- =====================================================================
--  RE+  PER-CUSTOMER PRICING  —  schema migration
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database the
--  desktop app + sync agent use) — NOT the cloud and NOT the box.
--
--  Adds four per-customer pricing columns to customer_details. For a
--  company whose  company.type = 'Re+' , bill opening/closing read these
--  instead of the company-wide slab tables (COMPANY_INTEREST,
--  COMPANY_DOCUMENT_CHARGE, COMPANY_FORMULA). When a column is NULL the
--  app falls back to the company slabs (chosen behaviour).
--
--  Idempotent — safe to run more than once.
-- =====================================================================

ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS interest        NUMERIC;   -- % per month, mirrors COMPANY_INTEREST.interest
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS document_charge NUMERIC;   -- flat doc charge, mirrors COMPANY_DOCUMENT_CHARGE.document_charge
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS open_formula    TEXT;      -- amount formula at OPEN,  mirrors COMPANY_FORMULA.formula (OPEN)
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS close_formula   TEXT;      -- amount formula at CLOSE, mirrors COMPANY_FORMULA.formula (CLOSE)

-- Verify
SELECT column_name, data_type
FROM   information_schema.columns
WHERE  table_name = 'customer_details'
  AND  column_name IN ('interest','document_charge','open_formula','close_formula')
ORDER  BY column_name;
