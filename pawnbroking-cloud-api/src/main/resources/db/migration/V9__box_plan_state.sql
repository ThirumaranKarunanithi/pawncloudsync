-- Each shop's box plan, beside how full the box is.
--
-- From 06-11-2026 a paid box plan ends by itself: reminders, then a week of
-- grace with the space kept, then the account's limit goes back to the free
-- 5 GB. A shop holding more than that has every upload refused - photos,
-- reports and backups - until the plan is renewed. The box mails the account's
-- own address (the shop's sign-in); these columns are how Magizhchi hears about
-- it too, and how the console shows which plan ends when.
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS plan_code       TEXT;
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS plan_label      TEXT;
-- FREE, ACTIVE, GRACE or EXPIRED, as the box reports it.
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS plan_state      TEXT;
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS plan_expires_at TIMESTAMPTZ;
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS grace_ends_at   TIMESTAMPTZ;
-- PENDING_PAYMENT or PENDING_VERIFICATION while a renewal is under way.
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS pending_payment TEXT;
-- The plan warning already sent, as "<kind>|<plan_expires_at>", so each one
-- (ENDING, GRACE, ENDED) goes out once per expiry date and a renewed plan that
-- later lapses again is warned about again.
ALTER TABLE public.box_quota ADD COLUMN IF NOT EXISTS plan_warned     TEXT;
