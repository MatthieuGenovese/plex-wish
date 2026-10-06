import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CastAdminEntry,
  CastSummary,
  IssuePage,
  MetadataEntry,
  MetadataSheet,
  MetadataSummary,
  Override,
  OverrideRequest,
  Page,
  PosterEntry,
  PosterSummary,
  Role,
  ScanReport,
  TmdbEntry,
  TmdbSheet,
  TmdbSummary,
  TmdbType,
  User, MediaEntry, MediaSummary, RemuxTestStatus } from './api-types';

export interface CreateUser {
  username: string;
  email: string | null;
  password: string;
  role: Role;
}

/** Champs absents = inchangés ; password = réinitialisation par l'admin. */
export interface UpdateUser {
  enabled?: boolean;
  role?: Role;
  password?: string;
}

export interface IssueQuery {
  scanId?: number;
  category?: string;
  anime?: string;
  page?: number;
  size?: number;
}

/** Administration : utilisateurs, scan, rapport, corrections (rôle ADMIN côté serveur). */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  users(): Observable<User[]> {
    return this.http.get<User[]>('/api/admin/users');
  }

  createUser(user: CreateUser): Observable<User> {
    return this.http.post<User>('/api/admin/users', user);
  }

  updateUser(id: number, changes: UpdateUser): Observable<User> {
    return this.http.patch<User>(`/api/admin/users/${id}`, changes);
  }

  startScan(confirmMassRemoval = false): Observable<{ scanId: number }> {
    const params = confirmMassRemoval ? new HttpParams().set('confirmMassRemoval', 'true') : undefined;
    return this.http.post<{ scanId: number }>('/api/admin/library/scan', null, { params });
  }

  /** Dernier scan, ou celui demandé. 404 NO_SCAN si aucun scan n'a encore été lancé. */
  scanReport(scanId?: number): Observable<ScanReport> {
    const params = scanId ? new HttpParams().set('scanId', scanId) : undefined;
    return this.http.get<ScanReport>('/api/admin/library/scan-report', { params });
  }

  scans(page: number, size = 20): Observable<Page<ScanReport>> {
    return this.http.get<Page<ScanReport>>('/api/admin/library/scans', { params: { page, size } });
  }

  issues(query: IssueQuery): Observable<IssuePage> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<IssuePage>('/api/admin/library/issues', { params });
  }

  /** 409 EPISODE_ALREADY_LINKED si l'épisode est déjà fourni par un autre fichier, sauf replace = true. */
  setOverride(mediaFileId: number, request: OverrideRequest, replace = false): Observable<Override> {
    const params = replace ? new HttpParams().set('replace', 'true') : undefined;
    return this.http.put<Override>(`/api/admin/library/files/${mediaFileId}/override`, request, { params });
  }

  deleteOverride(mediaFileId: number): Observable<void> {
    return this.http.delete<void>(`/api/admin/library/files/${mediaFileId}/override`);
  }

  overrides(): Observable<Override[]> {
    return this.http.get<Override[]>('/api/admin/library/overrides');
  }

  // --- Métadonnées ---------------------------------------------------------------------------------

  metadataSummary(): Observable<MetadataSummary> {
    return this.http.get<MetadataSummary>('/api/admin/metadata/summary');
  }

  metadata(query: { status?: string; q?: string; page?: number; size?: number }): Observable<Page<MetadataEntry>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<Page<MetadataEntry>>('/api/admin/metadata', { params });
  }

  metadataPreview(animeId: number, providerId: string): Observable<MetadataSheet> {
    return this.http.get<MetadataSheet>(`/api/admin/anime/${animeId}/metadata/preview`, { params: { providerId } });
  }

  /** providerId null = « aucune fiche ». 409 METADATA_CONFLICT sans replace s'il faut confirmer. */
  setMetadata(animeId: number, providerId: string | null, replace = false): Observable<MetadataEntry> {
    const params = replace ? new HttpParams().set('replace', 'true') : undefined;
    return this.http.put<MetadataEntry>(`/api/admin/anime/${animeId}/metadata`, { providerId }, { params });
  }

  unlockMetadata(animeId: number): Observable<void> {
    return this.http.delete<void>(`/api/admin/anime/${animeId}/metadata`);
  }

  requeueMetadata(status: string): Observable<{ requeued: number }> {
    return this.http.post<{ requeued: number }>('/api/admin/metadata/requeue', null, { params: { status } });
  }

  // --- TMDB ------------------------------------------------------------------------------------

  tmdbSummary(): Observable<TmdbSummary> {
    return this.http.get<TmdbSummary>('/api/admin/tmdb/summary');
  }

  /** noFrench : seulement les animés sans synopsis français. */
  tmdb(query: { status?: string; q?: string; noFrench?: boolean; page?: number; size?: number }): Observable<Page<TmdbEntry>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') params = params.set(k, String(v));
    }
    return this.http.get<Page<TmdbEntry>>('/api/admin/tmdb', { params });
  }

  tmdbPreview(animeId: number, type: TmdbType, tmdbId: number): Observable<TmdbSheet> {
    return this.http.get<TmdbSheet>(`/api/admin/anime/${animeId}/tmdb/preview`, { params: { type, tmdbId } });
  }

  /** tmdbId null : « aucune fiche TMDB » (synopsis anglais), verrouillé. */
  setTmdb(animeId: number, sheet: { type: TmdbType; tmdbId: number } | null, replace = false): Observable<TmdbEntry> {
    const params = replace ? new HttpParams().set('replace', 'true') : undefined;
    return this.http.put<TmdbEntry>(`/api/admin/anime/${animeId}/tmdb`, sheet ?? { type: null, tmdbId: null }, { params });
  }

  unlockTmdb(animeId: number): Observable<void> {
    return this.http.delete<void>(`/api/admin/anime/${animeId}/tmdb`);
  }

  requeueTmdb(status: string): Observable<{ requeued: number }> {
    return this.http.post<{ requeued: number }>('/api/admin/tmdb/requeue', null, { params: { status } });
  }

  /** Fin de licence TMDB : efface toutes les données TMDB. */
  purgeTmdb(): Observable<{ purged: number }> {
    return this.http.post<{ purged: number }>('/api/admin/tmdb/purge', null, { params: { confirm: 'true' } });
  }

  // --- Affiches ----------------------------------------------------------------------------------

  posterSummary(): Observable<PosterSummary> {
    return this.http.get<PosterSummary>('/api/admin/posters/summary');
  }

  posters(query: { filter?: string; q?: string; page?: number; size?: number }): Observable<Page<PosterEntry>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') params = params.set(k, String(v));
    }
    return this.http.get<Page<PosterEntry>>('/api/admin/posters', { params });
  }

  redownloadPoster(animeId: number): Observable<{ queued: boolean; running: boolean }> {
    return this.http.post<{ queued: boolean; running: boolean }>(`/api/admin/anime/${animeId}/poster/redownload`, null);
  }

  // --- Distribution ------------------------------------------------------------------------------

  castSummary(): Observable<CastSummary> {
    return this.http.get<CastSummary>('/api/admin/cast/summary');
  }

  castList(query: { filter?: string; q?: string; page?: number; size?: number }): Observable<Page<CastAdminEntry>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') params = params.set(k, String(v));
    }
    return this.http.get<Page<CastAdminEntry>>('/api/admin/cast', { params });
  }

  refreshCast(animeId: number): Observable<{ queued: boolean; running: boolean }> {
    return this.http.post<{ queued: boolean; running: boolean }>(`/api/admin/anime/${animeId}/cast/refresh`, null);
  }

  /** Efface toute la distribution (données et images). */
  purgeCast(): Observable<{ purged: number; running: boolean }> {
    return this.http.post<{ purged: number; running: boolean }>('/api/admin/cast/purge', null, { params: { confirm: 'true' } });
  }

  // --- Médias --------------------------------------------------------------------------------------

  mediaSummary(): Observable<MediaSummary> {
    return this.http.get<MediaSummary>('/api/admin/media/summary');
  }

  mediaFiles(query: { filter?: string; q?: string; page?: number; size?: number }): Observable<Page<MediaEntry>> {
    let params = new HttpParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') params = params.set(k, String(v));
    }
    return this.http.get<Page<MediaEntry>>('/api/admin/media/files', { params });
  }

  reprobe(mediaFileId: number): Observable<{ queued: boolean; running: boolean }> {
    return this.http.post<{ queued: boolean; running: boolean }>(`/api/admin/media/files/${mediaFileId}/reprobe`, null);
  }

  reprobeFailed(): Observable<{ queued: number; running: boolean }> {
    return this.http.post<{ queued: number; running: boolean }>('/api/admin/media/reprobe-failed', null);
  }

  remuxTest(): Observable<RemuxTestStatus> {
    return this.http.get<RemuxTestStatus>('/api/admin/media/remux-test');
  }

  /** {@code startAt} : ISO-8601, démarrage différé (24 h au plus). */
  startRemuxTest(startAt?: string): Observable<RemuxTestStatus> {
    return this.http.post<RemuxTestStatus>('/api/admin/media/remux-test/start', null, startAt ? { params: { startAt } } : {});
  }

  stopRemuxTest(): Observable<RemuxTestStatus> {
    return this.http.post<RemuxTestStatus>('/api/admin/media/remux-test/stop', null);
  }

  resetRemuxTest(): Observable<{ deleted: number }> {
    return this.http.post<{ deleted: number }>('/api/admin/media/remux-test/reset', null, { params: { confirm: 'true' } });
  }
}
