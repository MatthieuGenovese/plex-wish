# Construit et publie les images du serveur et du web (D1.1), depuis Windows. Même logique que publish-images.sh.
#
#   $env:GHCR_OWNER = "mon-compte-images"; scripts\publish-images.ps1          # construit, vérifie, publie
#   scripts\publish-images.ps1 -NoPush                                         # construit et vérifie seulement
#
# Avant la première publication : docker login ghcr.io avec un jeton qui a le droit write:packages
# (PAS le jeton en lecture donné au NAS). Voir docs/DEPLOIEMENT.md, « Pour moi : images ».
# Refus de publier si le dépôt a des modifications non commitées, si la version existe déjà sur le registre,
# ou si un secret est trouvé dans une image (scripts/image-scan.sh, dans un conteneur jetable).
param([switch]$NoPush)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail($msg) { Write-Host "ÉCHEC : $msg" -ForegroundColor Red; exit 1 }
function Run { & $args[0] $args[1..($args.Count - 1)]; if ($LASTEXITCODE -ne 0) { Fail "$($args -join ' ')" } }

$version = (Get-Content VERSION -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { Fail "VERSION invalide : '$version' (attendu 1.2.3)" }
if ($env:REGISTRY) { $registry = $env:REGISTRY }
elseif ($env:GHCR_OWNER) { $registry = "ghcr.io/$($env:GHCR_OWNER.ToLower())" }
else { Fail "définir `$env:GHCR_OWNER (compte ou organisation GitHub qui héberge les images)" }

$commit = (git rev-parse --short=12 HEAD).Trim()
if ((git status --porcelain --untracked-files=no) -and $env:ALLOW_DIRTY -ne "1") {
    Fail "le dépôt a des modifications non commitées : commiter d'abord (l'image doit correspondre à un commit)"
}
$backend = "$registry/anime-server-backend"
$web = "$registry/anime-server-web"

# Registre local d'essai, en HTTP : docker manifest a besoin de --insecure.
$insecure = @()
if ($registry -match '^(localhost|127\.0\.0\.1):') { $insecure = @("--insecure") }
if (-not $NoPush) {
    foreach ($img in @($backend, $web)) {
        # « manifest unknown » = version absente du registre, c'est le cas normal. Windows PowerShell transforme ce
        # message d'erreur de docker en exception quand $ErrorActionPreference vaut Stop : on le lit donc en texte.
        $ErrorActionPreference = "Continue"
        $answer = (docker manifest inspect @insecure "${img}:$version" 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $code = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        if ($code -eq 0) { Fail "${img}:$version existe déjà sur le registre : augmenter VERSION" }
        if ($answer -notmatch 'manifest unknown|not found|no such manifest') {
            Fail "registre injoignable ou accès refusé pour ${img} ($answer). Avez-vous fait « docker login ghcr.io » avec le jeton d'écriture ?"
        }
    }
}

Write-Host "== Construction $version (commit $commit)"
Run docker build --pull -f backend/Dockerfile --build-arg "APP_VERSION=$version" --build-arg "GIT_COMMIT=$commit" `
    -t "${backend}:$version" -t "${backend}:sha-$commit" .
Run docker build --pull --build-arg "APP_VERSION=$version" --build-arg "GIT_COMMIT=$commit" `
    -t "${web}:$version" -t "${web}:sha-$commit" web

Write-Host "== Recherche de secrets dans les images"
$work = Join-Path ([IO.Path]::GetTempPath()) ("anime-scan-" + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
try {
    # Valeurs des secrets locaux (jamais affichées), à partir de 12 caractères.
    $known = @()
    foreach ($f in @(".env", "android/local.properties", "android/keystore.properties", "android/app/keystore.properties")) {
        if (-not (Test-Path $f)) { continue }
        foreach ($line in Get-Content $f) {
            if ($line -match '^[A-Za-z0-9_.]*(SECRET|PASSWORD|TOKEN|KEY|PASS|secret|password|token|key|Password)[A-Za-z0-9_.]*=(.*)$') {
                $v = $Matches[2].Trim().Trim('"')
                if ($v.Length -ge 12) { $known += $v }
            }
        }
    }
    [IO.File]::WriteAllLines((Join-Path $work "known"), [string[]]$known)
    foreach ($img in @("${backend}:$version", "${web}:$version")) {
        Run docker save $img -o (Join-Path $work "image.tar")
        Run docker run --rm --network none -v "${work}:/scan:ro" -v "$root/scripts/image-scan.sh:/image-scan.sh:ro" `
            --entrypoint sh maven:3.9-eclipse-temurin-21 /image-scan.sh /scan/image.tar /scan/known $img
        Remove-Item (Join-Path $work "image.tar")
    }
} finally {
    Remove-Item -Recurse -Force $work
}

if ($NoPush) { Write-Host "== Images prêtes (non publiées) : ${backend}:$version ${web}:$version"; exit 0 }
Write-Host "== Publication"
foreach ($img in @($backend, $web)) {
    Run docker push "${img}:$version"
    Run docker push "${img}:sha-$commit"
}
Write-Host "== Publié : ${backend}:$version et ${web}:$version" -ForegroundColor Green
