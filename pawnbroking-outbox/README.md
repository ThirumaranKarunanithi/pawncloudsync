# pawnbroking-outbox

PostgreSQL outbox + change-capture triggers. **One install per shop machine.**

## Apply

```powershell
$env:PGPASSWORD = "yourpassword"
.\scripts\apply.ps1 -ShopId alwarpuram
.\scripts\apply.ps1 -ShopId annanagar   # on that shop's machine
```

`-ShopId` is the tenant key the cloud side uses to route writes into the
right schema (alwarpuram, annanagar, ...). Use **lowercase, no spaces**.

## What it does

- Creates `sync_outbox` table.
- Creates `sync_capture()` trigger function.
- Attaches it to every business table that exists locally (skips ones that
  don't, so it's safe to run on partial installs).
- Sets a database-level default for `app.shop_id` so all rows get tagged
  even when the desktop app forgets to `SET app.shop_id = '...'`.

## Verify

```sql
SELECT count(*) FROM sync_outbox;       -- before
-- click around in the desktop app (create a bill, edit a customer)
SELECT count(*) FROM sync_outbox;       -- should grow
SELECT shop_id, table_name, op, created_at
FROM sync_outbox ORDER BY created_at DESC LIMIT 10;
```
