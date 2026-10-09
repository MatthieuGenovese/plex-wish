# Mise à jour du NAS depuis le PC de Matthieu (D1.6) : publie les images de la version du fichier VERSION, puis
# lance deploy/update.sh sur le NAS par SSH, à travers Tailscale (docs/DEPLOIEMENT.md, « Pour moi : mettre à jour »).
#
#   scripts\update.ps1 -Nas nas-ami -User matthieu                 # publie puis met à jour
#   scripts\update.ps1 -Nas nas-ami -User matthieu -SkipPublish    # images déjà publiées
#
# Le NAS demande le mot de passe DSM (sudo) : la mise à jour tourne en root. Rien n'est automatique : chaque mise à
# jour est lancée ici, à un moment où l'on peut surveiller. update.sh sauvegarde, vérifie la santé et revient seul
# en arrière en cas d'échec.
param(
    [Parameter(Mandatory = $true)][string]$Nas,
    [Parameter(Mandatory = $true)][string]$User,
    [string]$ProjectPath = "/volume1/docker/anime-server",
    [switch]$SkipPublish
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content (Join-Path $root "VERSION") -Raw).Trim()

if (-not $SkipPublish) {
    & (Join-Path $PSScriptRoot "publish-images.ps1")
    if ($LASTEXITCODE -ne 0) { Write-Host "Publication en échec : pas de mise à jour." -ForegroundColor Red; exit 1 }
}

Write-Host "== Mise à jour du NAS $Nas vers $version"
ssh -t "$User@$Nas" "sudo sh '$ProjectPath/app/update.sh' $version --yes"
if ($LASTEXITCODE -ne 0) {
    Write-Host "La mise à jour a échoué : le NAS est revenu à la version précédente (voir backups/echec-maj-$version.log sur le NAS)." -ForegroundColor Red
    exit 1
}
Write-Host "== Version $version en service sur $Nas" -ForegroundColor Green
