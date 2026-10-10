# Génère la bibliothèque factice (≈ 33 000 fichiers vides) dans un volume Docker, depuis Windows.
# Les fichiers sont créés dans un conteneur Linux : certains noms seraient interdits sous Windows.
#
#   .\scripts\generate-fake-library.ps1                  → volume Docker "anime-fake-media"
#   .\scripts\generate-fake-library.ps1 -Volume autre    → autre nom de volume
#
# Puis : docker compose -f docker-compose.yml -f docker-compose.fake-media.yml up -d --build
param([string]$Volume = "anime-fake-media")
$ErrorActionPreference = "Stop"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..")
$sample = Join-Path $repo "backend\src\test\resources\library-sample.txt"
$scripts = Join-Path $repo "scripts\fake-library"
docker volume create $Volume | Out-Null
docker run --rm `
    -v "${Volume}:/media" `
    -v "${sample}:/sample.txt:ro" `
    -v "${scripts}:/scripts:ro" `
    alpine:3 sh /scripts/generate.sh
if ($LASTEXITCODE -ne 0) { throw "La génération a échoué (Docker Desktop est-il lancé ?)" }
