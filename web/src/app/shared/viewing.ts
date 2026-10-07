// Calculs d'affichage liés au visionnage (sans état, testés à part).

/** Un ajout est « nouveau » pendant 14 jours. */
export const NEW_DAYS = 14;

export function isNew(lastAddedAt: string | null | undefined, now = Date.now()): boolean {
  if (!lastAddedAt) return false;
  const t = Date.parse(lastAddedAt);
  return !Number.isNaN(t) && now - t < NEW_DAYS * 86_400_000 && t <= now + 60_000;
}

/** Minutes restantes (arrondi au supérieur, 1 au moins) ; null si la durée est inconnue. */
export function remainingMinutes(positionSeconds: number, durationSeconds: number): number | null {
  if (!durationSeconds || durationSeconds <= 0) return null;
  return Math.max(1, Math.ceil((durationSeconds - Math.min(positionSeconds, durationSeconds)) / 60));
}

/** Part regardée (0–100). */
export function percent(positionSeconds: number, durationSeconds: number): number {
  return durationSeconds > 0 ? Math.min(100, Math.max(0, Math.round((positionSeconds * 100) / durationSeconds))) : 0;
}

/** « S1 · É4 », « Spéciaux · É2 » (court, pour les cartes). */
export function shortEpisode(seasonNumber: number, episodeNumber: number): string {
  return `${seasonNumber === 0 ? 'Spéciaux' : 'S' + seasonNumber} · É${episodeNumber}`;
}

/** « Saison 1 · Épisode 4 », « Spéciaux · Épisode 2 ». */
export function longEpisode(seasonNumber: number, episodeNumber: number): string {
  return `${seasonNumber === 0 ? 'Spéciaux' : 'Saison ' + seasonNumber} · Épisode ${episodeNumber}`;
}

/** Durée d'un épisode en minutes arrondies (« 24 min ») ; null si inconnue. */
export function minutesLabel(seconds: number | null | undefined): string | null {
  return seconds && seconds > 0 ? `${Math.max(1, Math.round(seconds / 60))} min` : null;
}
