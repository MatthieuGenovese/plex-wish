# Catalogue de démonstration (outil de développement, phase Polish ; voir docs/DESIGN.md §6).
# Équivalent PowerShell de scripts/demo-catalog.sh :
#   scripts\demo-catalog.ps1          → génère, démarre la stack « plexwish-demo », charge les données
#   scripts\demo-catalog.ps1 down     → arrête la stack de démonstration et efface sa base
param([string]$Action = "up")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$env:WEB_PORT = if ($env:DEMO_WEB_PORT) { $env:DEMO_WEB_PORT } else { "8090" }
$env:DOCKER_SUBNET = if ($env:DEMO_DOCKER_SUBNET) { $env:DEMO_DOCKER_SUBNET } else { "172.30.65.0/24" }
$compose = @("compose", "-p", "plexwish-demo", "-f", "docker-compose.yml", "-f", "docker-compose.demo.yml")

if ($Action -eq "down") { docker @compose down -v; exit 0 }

docker run --rm -v "${PWD}/scripts/demo-catalog:/tool:ro" -v "${PWD}/demo-catalog:/out" python:3.13-alpine python -I /tool/generate.py /out
if ($LASTEXITCODE) { exit 1 }
docker @compose up -d --build
if ($LASTEXITCODE) { exit 1 }
Write-Host "Attente du backend…"
for ($i = 0; $i -lt 90; $i++) {
    $ok = docker @compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1 FROM app_user LIMIT 1"' 2>$null
    if ("$ok".Trim() -eq "1") { break }
    Start-Sleep 2
}
Get-Content -Raw -Encoding UTF8 demo-catalog/demo.sql | docker @compose exec -T postgres sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
Write-Host "Catalogue de démonstration prêt : http://localhost:$($env:WEB_PORT)"
