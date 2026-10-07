<#
  build-local-cloud.ps1

  Builds a LOCAL copy of one shop's cloud sync database — the same
  public schema and per-shop tenant schema Railway holds — and fills it
  from the shop database on this machine.

  The shop database is only ever READ. Nothing is written to it, no
  trigger fires there, and nothing is queued for the real cloud, so the
  sync agent service can stay running.

  Run from anywhere:
      powershell -ExecutionPolicy Bypass -File build-local-cloud.ps1

  Another shop:
      ... -ShopId karumbalai -DisplayName "Karumbalai Pawn Broking" `
          -OwnerEmail owner@example.com

  Every run is a full refresh of that tenant's events and projections.
#>
param(
    [string]  $ShopId      = 'iravathanallur',
    [string]  $DisplayName = 'Iravathanallur Pawn Broking',
    [string]  $OwnerEmail  = 'rajeshwariiravathanallur@gmail.com',
    [string[]]$AdminEmails = @('tirukaruna@gmail.com', 'neelamanikandank@gmail.com'),
    [string]  $CloudDb     = 'pawnbroking_cloud',
    [string]  $SyncProps   = 'C:\ProgramData\PawnBroking\sync.properties',
    [string]  $Psql        = '',
    # A full refresh briefly holds the old and new copies together, plus
    # sort spill. Below this, refuse rather than risk the shop's own DB.
    [double]  $MinFreeGB   = 1.0
)

# Native psql writes NOTICE lines to stderr; under 'Stop' Windows
# PowerShell would treat the first one as fatal. Failure is judged by
# exit code instead.
$ErrorActionPreference = 'Continue'

$here      = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo      = Split-Path -Parent (Split-Path -Parent $here)
$migDir    = Join-Path $repo 'pawnbroking-cloud-api\src\main\resources\db\migration'
$tenantSql = Join-Path $repo 'pawnbroking-cloud-api\src\main\resources\db\tenant\tenant.sql'

function Fail([string]$msg) { Write-Host "`nSTOPPED: $msg" -ForegroundColor Red; exit 1 }
function Step([string]$msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }

# ---- psql -----------------------------------------------------------------
if (-not $Psql) {
    $Psql = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [int]($_.Name -replace '\D','') } -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\psql.exe' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Psql -or -not (Test-Path $Psql)) { Fail 'psql.exe not found. Pass -Psql "C:\Program Files\PostgreSQL\<ver>\bin\psql.exe".' }

# ---- the shop database, from the agent's own settings -----------------------
if (-not (Test-Path $SyncProps)) { Fail "$SyncProps not found." }
$props = @{}
foreach ($line in Get-Content $SyncProps) {
    if ($line -match '^\s*([A-Za-z][\w.]*)\s*=\s*(.*)$') { $props[$matches[1]] = $matches[2].Trim() }
}
if ($props['db.url'] -notmatch '^jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?;]+)') {
    Fail "Could not read db.url from $SyncProps."
}
$dbHost = $matches[1]
$dbPort = if ($matches[2]) { $matches[2] } else { '5432' }
$shopDb = $matches[3]
$dbUser = $props['db.user']
$dbPass = $props['db.password']
if (-not $dbUser -or -not $dbPass) { Fail "db.user / db.password missing in $SyncProps." }

foreach ($p in @($migDir, $tenantSql, "$here\01_provision_tenant.sql", "$here\02_load_from_shop.sql", "$here\03_verify.sql")) {
    if (-not (Test-Path $p)) { Fail "Missing: $p" }
}
if ($AdminEmails.Count -lt 2) { $AdminEmails += @($OwnerEmail) * (2 - $AdminEmails.Count) }

# libpq connection string dblink uses to read the shop DB. Values are
# single-quoted with quotes and backslashes escaped, so an unusual
# password cannot break it.
function Q([string]$v) { "'" + ($v -replace '\\','\\' -replace "'","\'") + "'" }
$shopConn = "host=$(Q $dbHost) port=$(Q $dbPort) dbname=$(Q $shopDb) user=$(Q $dbUser) password=$(Q $dbPass)"

$env:PGPASSWORD = $dbPass
# Quiet the "already exists, skipping" notices every idempotent re-run prints.
$env:PGOPTIONS  = '-c client_min_messages=warning'

function Psql-Run([string]$Db, [string[]]$PsqlArgs) {
    # Out-Host matters: without it psql's result rows become part of this
    # function's return value, and every exit-code check below would be
    # comparing an array of text lines against 0.
    & $Psql -X -h $dbHost -p $dbPort -U $dbUser -d $Db -v ON_ERROR_STOP=1 @PsqlArgs | Out-Host
    return $LASTEXITCODE
}

# ---- disk: the mirror must never starve the shop's own database --------------
# The mirror lives in the same PostgreSQL cluster as the shop DB, so it
# shares its disk. If that disk fills, PostgreSQL cannot write WAL and the
# desktop app and sync agent stop with it. The first run of this script
# found C: at 170 MB and failed mid-load; this is what stops that.
$dataDir = & $Psql -X -h $dbHost -p $dbPort -U $dbUser -d postgres -tAc 'SHOW data_directory'
if ($LASTEXITCODE -ne 0 -or -not $dataDir) { Fail 'Could not reach PostgreSQL.' }
$dataDrive = $dataDir.Trim().Substring(0, 1)
$freeGB = [math]::Round((Get-PSDrive -Name $dataDrive).Free / 1GB, 2)
if ($freeGB -lt $MinFreeGB) {
    Fail ("Only $freeGB GB free on ${dataDrive}:, where PostgreSQL keeps every database including the shop's. " +
          "Free at least $MinFreeGB GB first. Nothing was changed.")
}

Write-Host "Shop database : $shopDb on ${dbHost}:$dbPort  (read only)"
Write-Host "Disk          : $freeGB GB free on ${dataDrive}: (PostgreSQL data)"
Write-Host "Local cloud   : $CloudDb"
Write-Host "Tenant        : $ShopId"

# ---- 1. the cloud database ----------------------------------------------------
Step "1/5  Cloud database '$CloudDb'"
$exists = & $Psql -X -h $dbHost -p $dbPort -U $dbUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$CloudDb'"
if ($LASTEXITCODE -ne 0) { Fail 'Could not reach PostgreSQL.' }
if ($CloudDb -notmatch '^[a-z_][a-z0-9_]*$') { Fail "Use a plain lower-case name for -CloudDb (got '$CloudDb')." }
if ($exists -ne '1') {
    # Unquoted on purpose: Windows PowerShell mangles embedded double quotes
    # in native arguments, and the name check above makes quoting unneeded.
    if ((Psql-Run 'postgres' @('-c', "CREATE DATABASE $CloudDb ENCODING 'UTF8' TEMPLATE template0")) -ne 0) {
        Fail "Could not create $CloudDb."
    }
    Write-Host "   created"
} else {
    Write-Host "   already there"
}

# ---- 2. public schema — the real Flyway migrations, in order -------------------
Step '2/5  Public schema (pawnbroking-cloud-api migrations)'
foreach ($m in Get-ChildItem $migDir -Filter 'V*__*.sql' | Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$','$1') }) {
    if ((Psql-Run $CloudDb @('-q', '-f', $m.FullName)) -ne 0) { Fail "Migration $($m.Name) failed." }
    Write-Host "   $($m.Name)"
}

# ---- 3. the tenant --------------------------------------------------------------
Step "3/5  Tenant '$ShopId'"
$provArgs = @('-q',
    '-v', "shop_id=$ShopId", '-v', "display_name=$DisplayName",
    '-v', "owner_email=$OwnerEmail", '-v', "admin1=$($AdminEmails[0])", '-v', "admin2=$($AdminEmails[1])",
    '-f', "$here\01_provision_tenant.sql")
if ((Psql-Run $CloudDb $provArgs) -ne 0) { Fail 'Provisioning failed.' }

# tenant.sql creates unqualified tables; the tenant schema must lead the
# search path, which is exactly how TenantBootstrap applies it.
$env:PGOPTIONS = "-c client_min_messages=warning -c search_path=$ShopId,public"
$rc = Psql-Run $CloudDb @('-q', '-f', $tenantSql)
$env:PGOPTIONS = '-c client_min_messages=warning'
if ($rc -ne 0) { Fail 'tenant.sql failed.' }
Write-Host "   tenant row, sign-in access, local sync key, schema $ShopId"

# ---- 4. load ----------------------------------------------------------------------
Step "4/5  Loading from $shopDb (read only)"
$sw = [Diagnostics.Stopwatch]::StartNew()
if ((Psql-Run $CloudDb @('-v', "shop_id=$ShopId", '-v', "shop_conn=$shopConn", '-f', "$here\02_load_from_shop.sql")) -ne 0) {
    Fail 'Load failed. The shop database was not changed.'
}
Write-Host ("   done in {0:n1}s" -f $sw.Elapsed.TotalSeconds)

# ---- 5. verify --------------------------------------------------------------------
Step '5/5  Mirror against the shop'
if ((Psql-Run $CloudDb @('-v', "shop_id=$ShopId", '-v', "shop_conn=$shopConn", '-f', "$here\03_verify.sql")) -ne 0) {
    Fail 'Verify failed.'
}

$env:PGPASSWORD = ''
$env:PGOPTIONS  = ''
Write-Host "`nLocal cloud ready: database $CloudDb, schema $ShopId." -ForegroundColor Green
