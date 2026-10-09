# Génère la commande que l'ami collera dans le Planificateur de tâches du DSM (D1.7). Rien n'est écrit dans le dépôt.
#
#   scripts\make-install-command.ps1 -Owner mon-compte-images -Media /volume1/animes -Domain mon-anime.duckdns.org
#
# Le jeton GitHub en LECTURE (read:packages, d'un compte dédié aux images de préférence) est demandé sans s'afficher.
# Il figure en clair dans la commande : elle s'envoie par un canal privé, et la tâche du Planificateur peut être
# supprimée après l'installation (le jeton reste enregistré par Docker sur le NAS pour les mises à jour).
param(
    [Parameter(Mandatory = $true)][string]$Owner,
    [Parameter(Mandatory = $true)][string]$Media,
    [Parameter(Mandatory = $true)][string]$Domain,
    [string]$LoginUser = "",
    [string]$Version = "",
    [switch]$Funnel
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Version) { $Version = (Get-Content (Join-Path $root "VERSION") -Raw).Trim() }
if (-not $LoginUser) { $LoginUser = $Owner }
$registry = "ghcr.io/$($Owner.ToLower())"
$secure = Read-Host "Jeton GitHub en lecture (read:packages)" -AsSecureString
$token = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ($token -notmatch '^(ghp_|github_pat_)[A-Za-z0-9_]+$') { Write-Host "Ce n'est pas un jeton GitHub." -ForegroundColor Red; exit 1 }
$extra = if ($Funnel) { " --funnel" } else { "" }

$lines = @(
    "set -e",
    "export PATH=/usr/local/bin:`$PATH",
    "echo '$token' | docker login ghcr.io -u $LoginUser --password-stdin",
    "docker run --rm --entrypoint cat $registry/anime-server-backend:$Version /app/deploy/install.sh > /tmp/anime-install.sh",
    "sh /tmp/anime-install.sh --version $Version --registry $registry --media '$Media' --domain $Domain$extra"
)
Write-Host ""
Write-Host "=== Commande d'installation (Planificateur de tâches, utilisateur root) ===" -ForegroundColor Cyan
$lines | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host "Pour une SIMULATION d'abord (rien n'est modifié) : ajouter  --dry-run  à la fin de la dernière ligne." -ForegroundColor Yellow
Write-Host "À envoyer par un message privé. Explication ligne par ligne : docs/DEPLOIEMENT.md, « La commande, expliquée »."
