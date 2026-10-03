// Types des réponses de l'API (miroir des records Java, écrits à la main : ARCHITECTURE §9).

export type Role = 'ADMIN' | 'USER';

export interface User {
  id: number;
  username: string;
  email: string | null;
  role: Role;
  enabled: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

/** Corps des erreurs de l'API (ErrorResponse). */
export interface ApiError {
  status: number;
  error: string;
  message: string;
}

export interface Page<T> {
  total: number;
  page: number;
  size: number;
  items: T[];
}

export interface AnimeSummary {
  id: number;
  title: string;
  year: number | null;
  posterUrl: string | null;
  episodeCount: number;
  lastAddedAt: string | null;
}

export interface Season {
  id: number;
  seasonNumber: number;
  label: string;
  episodeCount: number;
}

export interface AnimeDetail {
  id: number;
  title: string;
  alternativeTitle: string | null;
  synopsis: string | null;
  /** Langue du synopsis (ISO 639-1, ex. "en"). */
  synopsisLanguage: string | null;
  posterUrl: string | null;
  posterLargeUrl: string | null;
  year: number | null;
  /** Fournisseur de la fiche (« AniList »), null sans fiche. */
  metadataSource: string | null;
  metadataUrl: string | null;
  seasons: Season[];
  /** Origine du synopsis affiché : « TMDB » (français) ou « AniList » (anglais), null sans synopsis. */
  synopsisSource: string | null;
  /** Titre français (TMDB), s'il diffère du titre original. */
  frenchTitle: string | null;
  tmdbUrl: string | null;
}

export interface EpisodeSummary {
  id: number;
  episodeNumber: number;
  title: string | null;
  durationSeconds: number | null;
}

export type ScanStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';
export type ScanFailureCode = 'MEDIA_ROOT_UNAVAILABLE' | 'MASS_REMOVAL' | 'INTERRUPTED' | 'INTERNAL_ERROR';

export interface ScanStats {
  videos: number;
  episodes: number;
  extras: number;
  unresolved: number;
  duplicates: number;
  multiEpisodes: number;
  decimalEpisodes: number;
  seasonMismatches: number;
  overridesApplied: number;
  ignoredByOverride: number;
  newFiles: number;
  missing: number;
  rebranched: number;
  unreadable: number;
  knownFiles: number;
  animeCount: number;
  durationMs: number;
  otherFiles: Record<string, number>;
}

export interface ScanReport {
  id: number;
  status: ScanStatus;
  startedAt: string;
  finishedAt: string | null;
  triggeredBy: string;
  failureCode: ScanFailureCode | null;
  failureReason: string | null;
  stats: ScanStats | null;
  issueCounts: Record<string, number>;
}

export const ISSUE_CATEGORIES = [
  'UNRESOLVED',
  'DUPLICATE',
  'SEASON_MISMATCH',
  'MULTI_EPISODE',
  'DECIMAL_EPISODE',
  'MISSING',
  'UNREADABLE',
] as const;
export type IssueCategory = (typeof ISSUE_CATEGORIES)[number];

export const CATEGORY_LABELS: Record<IssueCategory, string> = {
  UNRESOLVED: 'Non résolus',
  DUPLICATE: 'Doublons',
  SEASON_MISMATCH: 'Désaccords dossier / fichier',
  MULTI_EPISODE: 'Épisodes doubles',
  DECIMAL_EPISODE: 'Numéros décimaux',
  MISSING: 'Disparus',
  UNREADABLE: 'Illisibles',
};

export interface Issue {
  id: number;
  mediaFileId: number | null;
  category: IssueCategory;
  animeTitle: string | null;
  relativePath: string;
  detail: string | null;
  seasonNumber: number | null;
  episodeNumber: number | null;
  keptRelativePath: string | null;
  seasonSource: string | null;
  keptSeasonSource: string | null;
}

export interface IssuePage extends Page<Issue> {
  scanId: number;
}

export type OverrideAction = 'EPISODE' | 'EXTRA' | 'IGNORE';

export interface OverrideRequest {
  action: OverrideAction;
  animeTitle?: string;
  seasonNumber?: number;
  episodeNumber?: number;
}

export interface Override {
  mediaFileId: number | null;
  relativePath: string;
  action: OverrideAction;
  animeTitle: string | null;
  seasonNumber: number | null;
  episodeNumber: number | null;
  createdBy: string;
  createdAt: string;
}

/** Fichier qui serait délié par une correction (viaOverride : lié par une autre correction). */
export interface LinkedFile {
  mediaFileId: number | null;
  relativePath: string;
  viaOverride: boolean;
}

/** Corps du 409 EPISODE_ALREADY_LINKED : rien n'a été modifié. */
export interface OverrideConflict extends ApiError {
  episode: { animeTitle: string; seasonNumber: number; episodeNumber: number };
  currentFiles: LinkedFile[];
  targetFile: LinkedFile;
}

// --- Métadonnées (administration) ----------------------------------------------------------------

export type MatchStatus = 'PENDING' | 'MATCHED' | 'DOUBTFUL' | 'UNMATCHED' | 'MANUAL';

export interface MetadataCandidate {
  providerId: string;
  title: string;
  romaji: string | null;
  year: number | null;
  format: string | null;
  episodes: number | null;
  posterUrl: string | null;
  siteUrl: string | null;
  score?: number;
}

export interface MetadataEntry {
  animeId: number;
  title: string;
  status: MatchStatus;
  reason: string | null;
  score: number | null;
  locked: boolean;
  providerId: string | null;
  matchedTitle: string | null;
  year: number | null;
  posterUrl: string | null;
  metadataUrl: string | null;
  candidates: MetadataCandidate[];
  lastError: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface MetadataSummary {
  enabled: boolean;
  provider: string;
  counts: Record<MatchStatus, number>;
  total: number;
  pausedUntil: string | null;
  lastUnavailable: string | null;
  estimatedMinutesLeft: number;
}

/** Fiche proposée ou actuelle (prévisualisation, confirmation). */
export interface MetadataSheet {
  providerId: string;
  title: string;
  romaji: string | null;
  year: number | null;
  format: string | null;
  episodes: number | null;
  synopsis: string | null;
  posterUrl: string | null;
  siteUrl: string | null;
}

/** Corps du 409 METADATA_CONFLICT : rien n'a été modifié. */
export interface MetadataConflict extends ApiError {
  anime: { id: number; title: string };
  current: MetadataSheet | null;
  proposed: MetadataSheet | null;
  otherAnime: { id: number; title: string }[];
}

// --- TMDB (synopsis en français) -------------------------------------------------------------

export type TmdbType = 'tv' | 'movie';

export interface TmdbSummary {
  /** false : pas de clé TMDB, synopsis anglais d'AniList uniquement. */
  configured: boolean;
  counts: Record<MatchStatus, number>;
  total: number;
  withFrenchSynopsis: number;
  /** Fiches de plus de 5 mois, à redemander (conditions de l'API TMDB). */
  refreshDue: number;
  pausedUntil: string | null;
  lastUnavailable: string | null;
}

export interface TmdbCandidate {
  type: TmdbType;
  tmdbId: number;
  name: string | null;
  originalName: string | null;
  year: number | null;
  animation: boolean;
  hasFrenchOverview: boolean;
  url: string;
  score?: number;
}

export interface TmdbEntry {
  animeId: number;
  title: string;
  status: MatchStatus;
  reason: string | null;
  score: number | null;
  locked: boolean;
  tmdbType: TmdbType | null;
  tmdbId: number | null;
  frenchTitle: string | null;
  hasFrenchSynopsis: boolean;
  fetchedAt: string | null;
  url: string | null;
  candidates: TmdbCandidate[];
  lastError: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface TmdbSheet {
  type: TmdbType;
  tmdbId: number;
  name: string | null;
  originalName: string | null;
  year: number | null;
  overview: string | null;
  animation: boolean;
  url: string;
}

/** Corps du 409 TMDB_CONFLICT : rien n'a été modifié. */
export interface TmdbConflict extends ApiError {
  animeId: number;
  animeTitle: string;
  current: TmdbSheet | null;
  proposed: TmdbSheet | null;
}
