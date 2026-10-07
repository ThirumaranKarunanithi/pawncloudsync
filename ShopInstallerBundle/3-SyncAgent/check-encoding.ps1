$p=@{}; Get-Content "C:\ProgramData\PawnBroking\sync.properties" | ?{$_ -match "^\s*[^#].*="} | %{$k,$v=$_ -split "=",2; $p[$k.Trim()]=$v.Trim()}
$env:PGPASSWORD=$p["db.password"]; $db=($p["db.url"] -split "/")[-1]
$psql=(Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe" | Select -Last 1).FullName
"SHOP : " + $p["shop.id"]
& $psql -h localhost -U $p["db.user"] -d $db -A -t -c "SELECT 'ENCODING : '||pg_encoding_to_char(encoding)||'   COLLATE : '||datcollate FROM pg_database WHERE datname=current_database();"
Remove-Item Env:PGPASSWORD
