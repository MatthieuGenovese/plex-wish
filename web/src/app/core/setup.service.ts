import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, firstValueFrom, map, of } from 'rxjs';
import { TokenResponse } from './api-types';

/** Porte d'entrée vue par le serveur : Internet (Caddy), réseau local du NAS, ou pile de développement. */
export type Entry = 'public' | 'lan' | 'local';

export interface SetupStatus {
  installed: boolean;
  entry: Entry;
  adminExists: boolean;
  publicUrl: string | null;
  version: string;
}

export interface SetupCheck {
  id: string;
  label: string;
  state: 'OK' | 'WARN' | 'FAIL';
  detail: string;
  fix: string | null;
}

export interface DiskThresholds {
  warnGb: number;
  criticalGb: number;
  remuxCapGb: number;
  /** Plafond du cache du lecteur web (Go) ; absent : inchangé. */
  webCapGb?: number | null;
}

/** Cache du lecteur web (WEB_CACHE_PATH de nas.env), éventuellement sur un autre volume. */
export interface WebCacheView {
  hostPath: string | null;
  totalBytes: number;
  freeBytes: number;
  usedBytes: number;
}

/** Valeurs du formulaire des seuils (tous les champs présents, pour FormGroup.setValue). */
export function diskFormValue(t: DiskThresholds) {
  return { warnGb: t.warnGb, criticalGb: t.criticalGb, remuxCapGb: t.remuxCapGb, webCapGb: t.webCapGb ?? 0 };
}

export interface DiskView {
  totalBytes: number;
  freeBytes: number;
  current: DiskThresholds;
  proposed: DiskThresholds;
  saved: boolean;
  webCache?: WebCacheView;
}

export interface TmdbView {
  configured: boolean;
  source: 'interface' | 'configuration' | null;
}

export interface DdnsStatus {
  domain: string | null;
  supported: boolean;
  configured: boolean;
  lastAttempt: string | null;
  lastOk: boolean | null;
  ip: string | null;
  message: string | null;
}

export interface ScanState {
  scanId: number | null;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | null;
  videos: number | null;
  episodes: number | null;
}

/**
 * Installation (D1.3) : état lu au démarrage, avant tout le reste. Tant que l'installation n'est pas terminée, ou si
 * le site est ouvert par l'adresse locale du NAS, seule la page /installation s'affiche. L'assistant garde son propre
 * jeton d'accès (pas de session : le réseau local est en HTTP, le cookie de session exige HTTPS).
 */
@Injectable({ providedIn: 'root' })
export class SetupService {
  private readonly http = inject(HttpClient);
  private token: string | null = null;

  readonly status = signal<SetupStatus | null>(null);
  /** La page d'installation remplace tout le site. */
  readonly takesOver = computed(() => {
    const s = this.status();
    return s !== null && (!s.installed || s.entry === 'lan');
  });

  /** Au démarrage de l'application. Ne rejette jamais (serveur injoignable : le site normal gère l'erreur). */
  load(): Promise<void> {
    return firstValueFrom(
      this.http.get<SetupStatus>('/api/setup/status').pipe(
        map((s) => this.status.set(s)),
        catchError(() => of(undefined)),
      ),
    );
  }

  refresh(): Observable<SetupStatus> {
    return this.http.get<SetupStatus>('/api/setup/status').pipe(map((s) => (this.status.set(s), s)));
  }

  hasToken(): boolean {
    return this.token !== null;
  }

  checks(): Observable<SetupCheck[]> {
    return this.http.get<SetupCheck[]>('/api/setup/checks');
  }

  createAdmin(username: string, password: string): Observable<void> {
    return this.http.post<TokenResponse>('/api/setup/admin', { username, password }).pipe(map((r) => this.keep(r)));
  }

  login(login: string, password: string): Observable<void> {
    return this.http.post<TokenResponse>('/api/setup/login', { login, password }).pipe(map((r) => this.keep(r)));
  }

  disk(): Observable<DiskView> {
    return this.http.get<DiskView>('/api/setup/disk', this.auth());
  }

  setDisk(t: DiskThresholds): Observable<DiskView> {
    return this.http.put<DiskView>('/api/setup/disk', t, this.auth());
  }

  ddns(): Observable<DdnsStatus> {
    return this.http.get<DdnsStatus>('/api/setup/ddns', this.auth());
  }

  setDdns(token: string): Observable<DdnsStatus> {
    return this.http.put<DdnsStatus>('/api/setup/ddns', { token }, this.auth());
  }

  tmdb(): Observable<TmdbView> {
    return this.http.get<TmdbView>('/api/setup/tmdb', this.auth());
  }

  setTmdb(token: string): Observable<TmdbView> {
    return this.http.put<TmdbView>('/api/setup/tmdb', { token }, this.auth());
  }

  startScan(): Observable<ScanState> {
    return this.http.post<ScanState>('/api/setup/scan', null, this.auth());
  }

  scan(): Observable<ScanState> {
    return this.http.get<ScanState>('/api/setup/scan', this.auth());
  }

  finish(): Observable<SetupStatus> {
    return this.http.post<SetupStatus>('/api/setup/finish', null, this.auth()).pipe(
      map((s) => {
        this.token = null;
        this.status.set(s);
        return s;
      }),
    );
  }

  private keep(r: TokenResponse): void {
    this.token = r.accessToken;
    this.status.update((s) => (s ? { ...s, adminExists: true } : s));
  }

  private auth(): { headers: HttpHeaders } {
    return { headers: new HttpHeaders(this.token ? { Authorization: `Bearer ${this.token}` } : {}) };
  }
}
