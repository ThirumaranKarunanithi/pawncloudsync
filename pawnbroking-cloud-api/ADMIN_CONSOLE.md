# The admin console

`https://<the cloud>/admin.html` — one page for the whole estate: how many
shops there are, which are still sending, what each one stores, who may sign
in, and what share of the monthly bill each shop accounts for.

Built into the cloud API itself, so there is nothing else to deploy or host.

## Signing in

A code is emailed to the address, exactly like the shop owners' login, and
Magizhchi Share is what sends and checks it. So an admin address must have a
Share account — without one, `Send code` answers "Not Found".

Who counts as an admin lives in `public.admin_users`, not in
`user_shop_access`: giving an owner access to their shop must never make them
an admin over every shop. The list is editable from the page, and the
`ADMIN_EMAILS` variable is read on every boot and adds anyone missing — that
is the way back in if the list is ever emptied. It never removes anyone.

    ADMIN_EMAILS=tirukaruna@gmail.com,neelamanikandank@gmail.com

The sign-in lasts 12 hours. Every call re-checks the address against
`admin_users`, so removing an admin takes effect at once rather than when
their token runs out.

## What the page shows

| | |
|---|---|
| shops | every tenant, live or switched off |
| sent something today / silent over 7 days / never synced | from the newest row in each shop's `events` table |
| no photo key yet | nobody has signed in on the phone for that shop, so photos and backups are refused with 503 |
| cloud database | `pg_total_relation_size` of that shop's schema |
| in Magizhchi Share | the bytes actually uploaded: `bill_images` + `backup_files` |
| cost | the monthly total, split by each shop's share of (database + Share) bytes |

The cost lines are yours to type — Railway, Share storage, domain, anything
else. Nothing is guessed: with every line at 0 the cost column simply reads
₹0. A shop that stores nothing gets an even share rather than a false 0%.

## What it can do

- **create a shop** — the schema and its tables, the tenant row, the owner's
  sign-in and the sync key, in one go. The key is shown ONCE, in the mbk_
  shape the setup exe expects. No Railway redeploy and no SQL console: it
  calls the same provisioning the app runs at boot.
  (The `TENANTS` variable is still worth keeping in step, so a fresh deploy
  re-provisions the same list.)
- **add or remove sign-in addresses** per shop. Removing is a soft revoke —
  the row stays, stamped, so it is still visible who had access and until when.
- **read or rotate a shop's sync key.** Rotating revokes the old one, which
  stops that shop PC syncing until the new key is in its `sync.properties`;
  the page says so before it does it.
- **rename a shop, or switch it off** (`tenants.active`).
- **add or remove admins** — never the last one, and never yourself.

## What it will not do

- delete a shop or its data. Nothing here drops a schema; that stays a
  deliberate act at the database.
- send anything to a shop PC. The agent is driven by
  `PawnBrokingSyncSetup.exe` at the shop, not from here.
- show a shop's bills. This page is about the estate, not the counter.

## Endpoints

All under `/v1/admin`, all needing `Authorization: Bearer <admin token>`
except the two login calls.

    POST   /login/send-otp        {email}
    POST   /login/verify          {email, code}          -> {token}
    GET    /overview
    GET    /shops
    POST   /shops                 {shop_id, display_name, email}
    PATCH  /shops/{id}            {display_name, active}
    POST   /shops/{id}/emails     {email, role}
    DELETE /shops/{id}/emails/{email}
    GET    /shops/{id}/api-key
    POST   /shops/{id}/api-key                            (rotate)
    GET    /admins
    POST   /admins                {email}
    DELETE /admins/{email}
    GET    /costs
    PUT    /costs                 [{name, monthly_amount}]

A shop id becomes a Postgres schema name, so it is checked against
`[a-z0-9_]{2,40}` before it is used anywhere, and the schema name is
re-validated at every place it is interpolated.

Refusals come back as `{"message": "...", "status": 400}` and the page shows
that sentence. That handler is scoped to this controller alone — the phones
and the sync agents keep the error format they already have.

## Tested

21-09-2026, against a local cloud database with three seeded tenants:
creating a shop from the page made its schema (5 tables), tenant row, owner
access and exactly one live `mbk_` key; adding and removing an address, the
key rotation, rename, switch-off, the admin list and the cost lines all
behaved; a forged token, a missing token, a non-admin address, a shop id with
spaces or capitals, a missing owner email and removing the last admin were
each refused with the sentence above.
