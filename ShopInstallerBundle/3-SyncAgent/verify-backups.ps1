# =====================================================================
#  VERIFY BACKUP UPLOADS  —  run on the shop PC, any time.
#
#  Answers one question: "is every file in the shop's backup folder
#  actually uploaded to the cloud?"  It compares the files ON DISK
#  against what the sync agent has recorded as successfully sent, and
#  lists anything still missing.
#
#  Reads its DB settings straight from the agent's own config, so there
#  is nothing to fill in:
#      C:\ProgramData\PawnBroking\sync.properties
#
#  USAGE (normal PowerShell, no admin needed):
#      powershell -ExecutionPolicy Bypass -File verify-backups.ps1
# =====================================================================

$ErrorActionPreference = "Stop"
$CONFIG = "C:\ProgramData\PawnBroking\sync.properties"

Write-Host ""
Write-Host "=== Backup upload verification ===" -ForegroundColor Cyan

if (-not (Test-Path $CONFIG)) { Write-Host "ERROR: config not found at $CONFIG" -ForegroundColor Red; exit 1 }

# ---- Read the agent's config -----------------------------------------
$cfg = @{}
foreach ($line in Get-Content $CONFIG) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $cfg[$matches[1].Trim()] = $matches[2].Trim() }
}
$dbUser = $cfg['db.user']
$dbPass = $cfg['db.password']
$shopId = $cfg['shop.id']
if ($cfg['db.url'] -match 'jdbc:postgresql://([^:/]+)(?::(\d+))?/(.+?)(\?.*)?$') {
    $dbHost = $matches[1]
    $dbPort = if ($matches[2]) { $matches[2] } else { "5432" }
    $dbName = $matches[3]
} else { Write-Host "ERROR: could not parse db.url" -ForegroundColor Red; exit 1 }

# ---- Locate psql.exe (highest installed version) ----------------------
$psql = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe","C:\Program Files (x86)\PostgreSQL\*\bin\psql.exe" `
        -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $psql) { Write-Host "ERROR: psql.exe not found under C:\Program Files\PostgreSQL" -ForegroundColor Red; exit 1 }

$env:PGPASSWORD = $dbPass
function Query([string]$sql) {
    & $psql.FullName -h $dbHost -p $dbPort -U $dbUser -d $dbName -t -A -c $sql 2>&1 |
        Where-Object { $_ -and $_.Trim() -ne "" }
}

Write-Host "Shop: $shopId    DB: $dbName@${dbHost}:$dbPort" -ForegroundColor Gray

# ---- Which folders does the agent watch? ------------------------------
$roots = Query "SELECT DISTINCT backup_file_path FROM company WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> ''"
if (-not $roots) { Write-Host "No backup_file_path configured in the company table." -ForegroundColor Yellow; exit 0 }

# ---- What has been uploaded already? ----------------------------------
$sent = @{}
foreach ($p in (Query "SELECT abs_path FROM sync_backup_uploads")) { $sent[$p.Trim().ToLower()] = $true }

# ---- Compare disk vs uploaded -----------------------------------------
$total = 0; $done = 0; $pending = @()
foreach ($root in $roots) {
    $root = $root.Trim()
    if (-not (Test-Path $root)) {
        Write-Host "WARNING: backup folder does not exist: $root" -ForegroundColor Yellow
        continue
    }
    Write-Host "Scanning: $root" -ForegroundColor Gray
    foreach ($f in Get-ChildItem -Path $root -Recurse -File -ErrorAction SilentlyContinue) {
        $total++
        if ($sent.ContainsKey($f.FullName.ToLower())) { $done++ }
        else { $pending += $f }
    }
}

# ---- Report ------------------------------------------------------------
Write-Host ""
Write-Host ("Files on disk : {0}" -f $total)
Write-Host ("Uploaded      : {0}" -f $done) -ForegroundColor Green
Write-Host ("Still pending : {0}" -f $pending.Count) -ForegroundColor $(if ($pending.Count) { "Yellow" } else { "Green" })

if ($pending.Count) {
    $mb = [math]::Round(($pending | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host ""
    Write-Host "Not yet in the cloud ($mb MB total) — oldest first:" -ForegroundColor Yellow
    $pending | Sort-Object LastWriteTime |
        Select-Object -First 40 @{n='File';e={$_.Name}},
                                 @{n='MB';e={[math]::Round($_.Length/1MB,1)}},
                                 @{n='Modified';e={$_.LastWriteTime.ToString('yyyy-MM-dd HH:mm')}} |
        Format-Table -AutoSize
    if ($pending.Count -gt 40) { Write-Host ("... and {0} more" -f ($pending.Count - 40)) }
    Write-Host "The agent uploads these on its own (scans once a minute)." -ForegroundColor Gray
    Write-Host "Leave the PC on and re-run this script to watch the number fall." -ForegroundColor Gray
    Write-Host "If it never falls, check:  pawnbroking-sync.err.log" -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "ALL BACKUP FILES ARE UPLOADED." -ForegroundColor Green
}
Write-Host ""
