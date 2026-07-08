-- =====================================================================
-- V5: Optional password login (alternative to email-OTP).
--
-- Adds a per-email password hash so the shop owner can sign in with a
-- password instead of waiting for an OTP email. OTP still works — this
-- is an additional path, not a replacement.
--
-- Password is stored per (email) in user_shop_access-adjacent form: we
-- add a small table keyed by email so one password covers all of that
-- email's shops (the shop is then chosen via the normal picker/JWT).
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.login_passwords (
    email          TEXT PRIMARY KEY,
    password_hash  TEXT NOT NULL,           -- BCrypt
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
