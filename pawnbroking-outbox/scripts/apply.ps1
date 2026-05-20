# =====================================================================
# Apply outbox migration to a local PostgreSQL.
# Usage:
#   .\apply.ps1 -ShopId alwarpuram
#   .\apply.ps1 -ShopId annanagar -PGDatabase pawnbroking -PGUser postgres
# =====================================================================
param(
    [Parameter(Mandatory=$true)] [string]$ShopId,
    [string]$PGHost = "localhost",
    [int]   $PGPort = 5432,
    [string]$PGUser = "postgres",
    [string]$PGDatabase = "pawnbroking",
    [string]$PGPassword = $env:PGPASSWORD
)

if (-not $PGPassword) {
    Write-Host "PGPASSWORD not set. Set `$env:PGPASSWORD or pass -PGPassword." -ForegroundColor Red
    exit 1
}

$env:PGPASSWORD = $PGPassword
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$migration = Join-Path $scriptDir "..\migrations\V1__sync_outbox.sql"

Write-Host "Applying outbox migration to $PGDatabase@${PGHost}:$PGPort for shop=$ShopId"
& psql -h $PGHost -p $PGPort -U $PGUser -d $PGDatabase -v ON_ERROR_STOP=1 -f $migration
if ($LASTEXITCODE -ne 0) { Write-Host "psql failed" -ForegroundColor Red; exit 1 }

# Persist shop_id as a database-level default so even sessions that
# forget to SET app.shop_id still tag rows correctly.
$sql = "ALTER DATABASE $PGDatabase SET app.shop_id = '$ShopId';"
& psql -h $PGHost -p $PGPort -U $PGUser -d $PGDatabase -v ON_ERROR_STOP=1 -c $sql
if ($LASTEXITCODE -ne 0) { Write-Host "shop_id set failed" -ForegroundColor Red; exit 1 }

Write-Host "DONE. Outbox installed; default shop_id = $ShopId" -ForegroundColor Green
