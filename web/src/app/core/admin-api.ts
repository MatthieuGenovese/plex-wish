import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  IssuePage,
  MetadataEntry,
  MetadataSheet,
  MetadataSummary,
  Override,
  OverrideRequest,
  Page,
  Role,
  ScanReport,
  User,
} from './api-types';

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
}
