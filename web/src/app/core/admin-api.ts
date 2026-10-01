import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { IssuePage, Override, OverrideRequest, Page, Role, ScanReport, User } from './api-types';

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

  setOverride(mediaFileId: number, request: OverrideRequest): Observable<Override> {
    return this.http.put<Override>(`/api/admin/library/files/${mediaFileId}/override`, request);
  }

  deleteOverride(mediaFileId: number): Observable<void> {
    return this.http.delete<void>(`/api/admin/library/files/${mediaFileId}/override`);
  }

  overrides(): Observable<Override[]> {
    return this.http.get<Override[]>('/api/admin/library/overrides');
  }
}
