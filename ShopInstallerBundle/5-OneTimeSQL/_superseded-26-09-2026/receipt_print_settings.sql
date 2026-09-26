-- =====================================================================
--  RECEIPT PRINTING  —  a printer, and PROMPT or DIRECT, for every receipt
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    Company Module → Gold Settings / Silver Settings → Print And Camera
--    Settings now has a RECEIPT PRINTING card. For each receipt —
--      Bill Opening, Bill Closing, Advance Amount, Rebill, Bill Calculator —
--    it keeps the printer (the computer's default, a named printer, or
--    DO NOT PRINT) and whether the receipt opens in the print preview
--    first (PROMPT) or prints straight away (DIRECT).
--
--  WHERE IT LIVES
--    Its own table, one row per company + material + receipt, not more
--    columns on company_other_settings: a new receipt later is a new row,
--    not another script. The cloud does not copy it — printer names belong
--    to the shop's own computers.
--
--  DEFAULT
--    No rows. A receipt with no row prints exactly as before this change:
--    the default printer, through the print preview. Until this script has
--    run, the desktop app keeps that behaviour and Company Module's Save
--    Settings says the table is missing.
-- =====================================================================


CREATE TABLE IF NOT EXISTS company_receipt_print_settings (
    company_id           VARCHAR(50)  NOT NULL,
    jewel_material_type  VARCHAR(10)  NOT NULL,      -- GOLD / SILVER
    receipt              VARCHAR(40)  NOT NULL,      -- BILL_OPENING, BILL_CLOSING, ADVANCE, REBILL, BILL_CALCULATOR
    printer_name         VARCHAR(200),               -- NULL = the computer's default printer; 'DO NOT PRINT' = none
    print_directly       BOOLEAN      NOT NULL DEFAULT FALSE,   -- FALSE = PROMPT (preview first)
    PRIMARY KEY (company_id, jewel_material_type, receipt)
);


-- Check what you have -------------------------------------------------
SELECT company_id,
       jewel_material_type,
       receipt,
       COALESCE(printer_name, 'DEFAULT PRINTER') AS printer,
       CASE WHEN print_directly THEN 'DIRECT' ELSE 'PROMPT' END AS prints
  FROM company_receipt_print_settings
 ORDER BY company_id, jewel_material_type, receipt;
