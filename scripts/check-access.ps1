# Vérification de l'accès Internet (D1.7), version Windows de check-access.sh : CGNAT, nom de domaine, port ouvert,
# boucle locale (hairpin). Depuis un PC du réseau de l'ami, puis depuis l'extérieur (-Outside) :
#
#   powershell -ExecutionPolicy Bypass -File check-access.ps1 -Domain mon-anime.duckdns.org -WanIp 88.12.34.56
#
# -WanIp : l'adresse IPv4 « WAN » / « Internet » affichée par la box. Seuls api.ipify.org (adresse publique, sans
# compte) et le site lui-même sont contactés.
param(
    [Parameter(Mandatory = $true)][string]$Domain,
    [string]$WanIp = "",
    [switch]$Outside
)
$problems = 0
function Bad($msg) { Write-Host "  X $msg" -ForegroundColor Red; $script:problems++ }
function Good($msg) { Write-Host "  OK $msg" -ForegroundColor Green }
function IsReserved($ip) {
    $b = $ip.Split('.') | ForEach-Object { [int]$_ }
    return ($b[0] -eq 10) -or ($b[0] -eq 192 -and $b[1] -eq 168) -or ($b[0] -eq 172 -and $b[1] -ge 16 -and $b[1] -le 31) -or
           ($b[0] -eq 100 -and $b[1] -ge 64 -and $b[1] -le 127)
}

Write-Host "== 1. Adresse publique vue d'Internet"
try { $public = (Invoke-RestMethod -Uri "https://api.ipify.org" -TimeoutSec 10).Trim(); Write-Host "  adresse publique : $public" }
catch { $public = ""; Bad "impossible de joindre api.ipify.org : pas d'accès Internet depuis ce PC ?" }

if (-not $Outside) {
    Write-Host "== 2. CGNAT (box qui partage son adresse avec d'autres clients du fournisseur)"
    if (-not $WanIp) { Write-Host "  ? relancer avec -WanIp <adresse IPv4 Internet affichée par la box> pour conclure." }
    elseif (IsReserved $WanIp) { Bad "la box a une adresse $WanIp réservée (privée ou CGNAT) : pas d'adresse publique à elle. → IPv4 full-stack / dédiée auprès du fournisseur, ou plan B (Tailscale Funnel)." }
    elseif ($public -and $WanIp -ne $public) { Bad "la box annonce $WanIp mais Internet voit $public : CGNAT (ou second routeur). → même conduite." }
    else { Good "pas de CGNAT : la box a sa propre adresse publique ($WanIp)." }
}

Write-Host "== 3. Nom de domaine"
try {
    $resolved = ([System.Net.Dns]::GetHostAddresses($Domain) | Where-Object { $_.AddressFamily -eq 'InterNetwork' } | Select-Object -First 1).IPAddressToString
    if (-not $resolved) { throw "aucune adresse" }
    if (-not $Outside -and $public -and $resolved -ne $public) { Bad "$Domain pointe vers $resolved au lieu de $public → DuckDNS pas encore à jour (5 min) ou jeton faux (Administration > Réglages)." }
    else { Good "$Domain → $resolved" }
} catch { Bad "$Domain ne correspond à aucune adresse → vérifier le nom et le jeton DuckDNS." }

Write-Host "== 4. Site en HTTPS (port 443 de la box, redirigé vers le NAS)"
try {
    $r = Invoke-WebRequest -Uri "https://$Domain/api/setup/status" -UseBasicParsing -TimeoutSec 15
    if ($r.StatusCode -eq 200) { Good "le site répond, certificat valide." } else { Bad "réponse inattendue ($($r.StatusCode))" }
} catch {
    $resp = $_.Exception.Response
    if ($resp) { Bad "le site répond, mais avec le code $([int]$resp.StatusCode) (adresse d'un autre site ?)"; $open = $null }
    else { $open = $false }
    if ($null -ne $open) { try { $c = New-Object System.Net.Sockets.TcpClient; $open = $c.ConnectAsync($Domain, 443).Wait(5000) -and $c.Connected; $c.Close() } catch { } }
    if ($null -eq $open) { }
    elseif ($open) { Bad "le port 443 répond mais pas en HTTPS valide → certificat pas encore obtenu (attendre quelques minutes) ou redirection vers le mauvais port (8443 attendu)." }
    elseif ($Outside) { Bad "pas de réponse depuis l'extérieur → redirection 443 TCP vers NAS:8443, pare-feu du DSM (autoriser 8443), ou CGNAT." }
    else { Bad "pas de réponse depuis la maison. Si le test -Outside (4G) marche : la box ne fait pas la boucle locale (hairpin). Sinon : redirection du port 443 absente ou mauvaise." }
}

Write-Host ""
if ($problems -eq 0) { Write-Host "Tout est en ordre." -ForegroundColor Green } else { Write-Host "$problems point(s) à régler (conduite à tenir ci-dessus)." -ForegroundColor Yellow }
exit $problems
