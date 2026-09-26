-- =====================================================================
--  BILL OPENING  —  the Accepted Closing term a new bill starts on
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    Gold and Silver Bill Opening start every new bill on a fixed term
--    (1Y for gold, 6M for silver; 1Y in the lite app), which sets the
--    Accepted Closing Date. Some companies remind the customer within
--    3 months, so each company now chooses its own term — 1Y, 9M, 6M,
--    3M or 1M — in Company Module → Gold Settings / Silver Settings →
--    Other Settings → Accepted Closing Term.
--
--  WHERE IT LIVES
--    On company_other_settings, the per-material row beside default
--    purity, city and area: gold and silver can differ.
--
--  DEFAULT
--    NULL — Bill Opening keeps the term it has always started on.
--    Nothing changes until someone saves a term in Company Module.
-- =====================================================================


ALTER TABLE company_other_settings
    ADD COLUMN IF NOT EXISTS default_closing_term VARCHAR(3);


-- Check what you have -------------------------------------------------
SELECT company_id,
       jewel_material_type,
       COALESCE(default_closing_term, '(screen default)') AS accepted_closing_term
  FROM company_other_settings
 ORDER BY company_id, jewel_material_type;
