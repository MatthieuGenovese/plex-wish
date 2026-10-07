# Essais à la main de l'API (phase Polish, P2.0)

Sur la stack de démonstration (`scripts\demo-catalog.ps1`, http://localhost:8090) : elle a 1 300 animés, des progressions et des comptes. Sur la stack normale, remplacer le port (8080) et l'animé de l'exemple.
Commandes PowerShell (Windows 10/11, PowerShell 5 ou 7) ; l'équivalent `curl` suit chaque bloc.

## 0. Se connecter

L'endpoint de l'app (`/api/auth/app/login`) renvoie les jetons dans la réponse : pratique pour les essais (aucun cookie).

```powershell
$B = "http://localhost:8090"
function Connect-Api($user, $password) {
  $r = Invoke-RestMethod -Method Post "$B/api/auth/app/login" -ContentType "application/json; charset=utf-8" `
       -Body (@{ login = $user; password = $password; device = "Essais PowerShell" } | ConvertTo-Json)
  $script:H = @{ Authorization = "Bearer $($r.accessToken)" }
  $script:REFRESH = $r.refreshToken
  "Connecté : $($r.user.username) ($($r.user.role))"
}
function Api($path) { Invoke-RestMethod "$B$path" -Headers $script:H }
Connect-Api "admin" "admin-password-123"   # mot de passe du .env (INITIAL_ADMIN_PASSWORD)
```

Le jeton d'accès dure 15 minutes : relancer `Connect-Api` ensuite.

```sh
B=http://localhost:8090
T=$(curl -s -H 'Content-Type: application/json' -d '{"login":"admin","password":"admin-password-123"}' $B/api/auth/app/login | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
```

## S1. « À suivre » : `GET /api/me/continue-watching`

Une entrée par animé : `RESUME` (épisode commencé) ou `NEXT` (épisode suivant d'un épisode terminé).

```powershell
Api "/api/me/continue-watching" | Format-Table kind, animeTitle, seasonLabel, episodeNumber, positionSeconds, durationSeconds
```

Vérifier le passage à l'épisode suivant : prendre une ligne `RESUME`, terminer l'épisode, relire.

```powershell
$e = (Api "/api/me/continue-watching" | Where-Object kind -eq "RESUME" | Select-Object -First 1)
"$($e.animeTitle) : épisode $($e.episodeNumber)"
Invoke-RestMethod -Method Put "$B/api/episodes/$($e.episodeId)/progress" -Headers $H -ContentType "application/json" `
  -Body (@{ positionSeconds = 1400; durationSeconds = 1400 } | ConvertTo-Json)
Api "/api/me/continue-watching" | Where-Object animeId -eq $e.animeId | Format-Table kind, episodeNumber, positionSeconds
```

Attendu : le même animé, en tête de liste, `NEXT` sur l'épisode suivant (position 0). Après le dernier épisode d'une série (hors Spéciaux), l'animé disparaît de la liste.

```sh
curl -s -H "Authorization: Bearer $T" "$B/api/me/continue-watching?limit=5"
```
