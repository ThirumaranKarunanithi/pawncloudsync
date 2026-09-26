-- =====================================================================
--  RE+  PER-CUSTOMER PRICING  —  DATE-VERSIONED
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    customer_details.interest held ONE value per customer with no dates,
--    so a customer whose rate changed ("1.25% until 24-08-2024, 1% after")
--    could not be expressed. Worse, the bill-opening screen re-read that
--    single value every time it recalculated, so opening an OLD bill
--    painted TODAY's rate over it — and pressing Update then saved the
--    wrong rate onto that old bill.
--
--  WHAT THIS ADDS
--    customer_pricing: one row per rate PERIOD per customer, looked up by
--    the BILL's opening date exactly like COMPANY_INTEREST does with
--    DATE_FROM / DATE_TO. Old bills keep the rate they were opened under.
--
--  LOOKUP ORDER used by the app (first hit wins):
--    1. customer_pricing row whose date range covers the bill date
--    2. customer_details.interest / document_charge / formulas  (legacy)
--    3. the company slab tables (COMPANY_INTEREST etc.)
-- =====================================================================


-- 1. The dated pricing table -------------------------------------------
CREATE TABLE IF NOT EXISTS customer_pricing (
    id              BIGSERIAL PRIMARY KEY,
    company_id      VARCHAR(50)  NOT NULL,
    customer_id     VARCHAR(50)  NOT NULL,
    interest        NUMERIC,          -- % per month; NULL = fall back
    document_charge NUMERIC,          -- flat charge;  NULL = fall back
    open_formula    TEXT,             -- NULL = fall back
    close_formula   TEXT,             -- NULL = fall back
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL DEFAULT DATE '2999-12-31',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- One rate period per customer per start date. The app closes the
-- previous period automatically, so ranges never overlap.
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_pricing_period
    ON customer_pricing (company_id, customer_id, date_from);

-- The hot path: "which rate applied to this customer on this date?"
CREATE INDEX IF NOT EXISTS ix_customer_pricing_lookup
    ON customer_pricing (company_id, customer_id, date_from, date_to);


-- 2. Carry the existing single values across ---------------------------
--    Anything already set on customer_details becomes an open-ended
--    period starting 1900-01-01, so behaviour is unchanged until the
--    owner adds a new dated rate. Runs only once (ON CONFLICT).
INSERT INTO customer_pricing
       (company_id, customer_id, interest, document_charge,
        open_formula, close_formula, date_from, date_to)
SELECT cd.company_id,
       cd.customer_id,
       cd.interest,
       cd.document_charge,
       cd.open_formula,
       cd.close_formula,
       DATE '1900-01-01',
       DATE '2999-12-31'
  FROM customer_details cd
 WHERE cd.customer_id IS NOT NULL
   AND trim(cd.customer_id) <> ''
   AND (cd.interest        IS NOT NULL
     OR cd.document_charge IS NOT NULL
     OR (cd.open_formula   IS NOT NULL AND trim(cd.open_formula)  <> '')
     OR (cd.close_formula  IS NOT NULL AND trim(cd.close_formula) <> ''))
ON CONFLICT (company_id, customer_id, date_from) DO NOTHING;


-- 3. Check what you have ------------------------------------------------
SELECT company_id, customer_id, interest, document_charge,
       date_from, date_to
FROM customer_pricing
ORDER BY company_id, customer_id, date_from;


-- =====================================================================
--  HOW THE OWNER USES IT (Customer Details -> Add Re+ New Customers)
--
--    Pick the customer, type the NEW interest / document charge /
--    formulas, set "Effective From" to the day the new rate starts, and
--    press "Add Rate".
--
--    The previous period is closed automatically the day before, e.g.
--        1.25%   1900-01-01 .. 2024-08-24
--        1.00%   2024-08-25 .. 2999-12-31
--    Bills opened on or before 24-08-2024 keep 1.25%; later bills get 1%.
--    Nothing rewrites an existing bill.
-- =====================================================================
