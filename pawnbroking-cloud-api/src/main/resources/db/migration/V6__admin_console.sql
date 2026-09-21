-- =====================================================================
-- V6: the admin console.
--
--   admin_users   who may open /admin.html. Seeded from the ADMIN_EMAILS
--                 env var on boot (AdminBootstrap), and editable from the
--                 page itself. Kept apart from user_shop_access on
--                 purpose: an owner who can see one shop must not become
--                 an admin over every shop by being added to it.
--
--   cost_items    what the pawn cloud costs each month, in lines you can
--                 edit (Railway, Magizhchi Share, domain, ...). The page
--                 splits the total across shops by how much each one
--                 actually stores, so "% of total cost" is a real number
--                 and not a guess.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.admin_users (
    email       TEXT PRIMARY KEY,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by    TEXT,
    revoked_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.cost_items (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    monthly_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency        TEXT NOT NULL DEFAULT 'INR',
    note            TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A starting point the first time only; every line is editable and
-- deletable from the page. Amounts are 0 so nothing here pretends to be
-- a real bill until someone types the real one.
INSERT INTO public.cost_items (name, monthly_amount, note)
SELECT * FROM (VALUES
    ('Railway (cloud API + database)', 0.00, 'the monthly Railway bill'),
    ('Magizhchi Share storage',        0.00, 'the plan that holds photos and backups'),
    ('Domain and other',               0.00, NULL)
) AS seed(name, monthly_amount, note)
WHERE NOT EXISTS (SELECT 1 FROM public.cost_items);
