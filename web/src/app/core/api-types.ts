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
  posterUrl: string | null;
  year: number | null;
  seasons: Season[];
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
