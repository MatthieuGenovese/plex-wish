import { WebAudioTrack, WebPlayback, WebSubtitleTrack } from '../../core/playback-api';

/** Règles du lecteur web sans DOM (testées seules) : temps, choix des pistes, reprise, préférences. */

/** « 12:34 », « 1:02:03 ». */
export function formatTime(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return (h > 0 ? h + ':' : '') + mm + ':' + String(sec).padStart(2, '0');
}

/** Pour un lecteur d'écran : « 12 minutes 34 secondes ». */
export function spokenTime(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const parts: string[] = [];
  if (h) parts.push(h + (h > 1 ? ' heures' : ' heure'));
  if (m) parts.push(m + (m > 1 ? ' minutes' : ' minute'));
  if (sec || parts.length === 0) parts.push(sec + (sec > 1 ? ' secondes' : ' seconde'));
  return parts.join(' ');
}

const FRENCH = ['fre', 'fra', 'fr'];

function sameLanguage(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false;
  const norm = (x: string) => (FRENCH.includes(x) ? 'fr' : x === 'jpn' ? 'ja' : x === 'eng' ? 'en' : x);
  return norm(a) === norm(b);
}

/** Piste audio : la langue préférée si elle existe, sinon la première (le serveur met le japonais en tête). */
export function pickAudio(tracks: WebAudioTrack[], preferred: string | null): number {
  const i = preferred ? tracks.findIndex((t) => sameLanguage(t.language, preferred)) : -1;
  return i >= 0 ? i : 0;
}

/**
 * Sous-titres : « off » mémorisé → aucun ; sinon la langue préférée, puis le français (pas les « forcés » seuls),
 * puis la piste marquée par défaut, puis la première. Null s'il n'y en a pas.
 */
export function pickSubtitle(tracks: WebSubtitleTrack[], preferred: string | null): number | null {
  if (tracks.length === 0 || preferred === 'off') return null;
  const full = tracks.filter((t) => !t.forced);
  const pool = full.length ? full : tracks;
  const byLang = (lang: string) => pool.find((t) => sameLanguage(t.language, lang));
  const t = (preferred && byLang(preferred)) || byLang('fre') || pool.find((x) => x.isDefault) || pool[0];
  return t ? t.id : null;
}

/** Position de reprise : la position enregistrée, sauf épisode fini, tout début ou toute fin. */
export function resumeFrom(info: Pick<WebPlayback, 'resume' | 'durationSeconds'>): number {
  const r = info.resume;
  if (!r || r.completed || r.positionSeconds < 10) return 0;
  const duration = info.durationSeconds ?? r.durationSeconds;
  if (duration && r.positionSeconds > duration - 30) return 0;
  return r.positionSeconds;
}

/** Position envoyée au serveur seulement si elle a un sens (comme sur Android). */
export function reportable(position: number, duration: number): boolean {
  return Number.isFinite(position) && Number.isFinite(duration) && duration >= 1 && position >= 1 && position <= duration + 1;
}

/** « Saison 1 · Épisode 3 · Titre » (« Spéciaux » pour la saison 0). */
export function episodeLine(e: WebPlayback['episode']): string {
  const season = e.seasonNumber === 0 ? 'Spéciaux' : 'Saison ' + e.seasonNumber;
  return [season, 'Épisode ' + e.episodeNumber, e.title].filter(Boolean).join(' · ');
}

// --- Préférences de l'appareil (pratiques seulement : tout marche sans) ----------------------------------------------

const PREFIX = 'anime.player.';

export function loadPref(key: 'audio' | 'subtitles' | 'volume'): string | null {
  try {
    return globalThis.localStorage?.getItem(PREFIX + key) ?? null;
  } catch {
    return null;
  }
}

export function savePref(key: 'audio' | 'subtitles' | 'volume', value: string): void {
  try {
    globalThis.localStorage?.setItem(PREFIX + key, value);
  } catch {
    // navigation privée, stockage plein : sans importance
  }
}
