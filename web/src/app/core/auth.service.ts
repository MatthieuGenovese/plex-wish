import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, firstValueFrom, map, of, shareReplay, tap, throwError } from 'rxjs';
import { TokenResponse, User } from './api-types';

/**
 * Session (ARCHITECTURE §5.1) : le token d'accès reste EN MÉMOIRE (jamais localStorage, risque XSS) ;
 * le refresh token est dans un cookie HttpOnly que le JavaScript ne voit pas. Au démarrage (F5),
 * restoreSession() demande un nouveau token d'accès avec ce cookie : l'utilisateur reste connecté.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly token = signal<string | null>(null);
  private readonly currentUser = signal<User | null>(null);
  /** Refresh en cours, partagé : jamais deux appels /api/auth/refresh en parallèle. */
  private refreshInFlight: Observable<string> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly isLoggedIn = computed(() => this.currentUser() !== null);
  readonly isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');

  accessToken(): string | null {
    return this.token();
  }

  login(login: string, password: string): Observable<User> {
    return this.http.post<TokenResponse>('/api/auth/login', { login, password }).pipe(
      tap((r) => this.setSession(r)),
      map((r) => r.user),
    );
  }

  /**
   * Nouveau token d'accès à partir du cookie. Les appels simultanés reçoivent le même résultat.
   * Un 401 (session expirée ou révoquée) efface la session locale.
   */
  refresh(): Observable<string> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.http.post<TokenResponse>('/api/auth/refresh', null).pipe(
        tap((r) => this.setSession(r)),
        map((r) => r.accessToken),
        catchError((err: unknown) => {
          if (err instanceof HttpErrorResponse && err.status === 401) {
            this.clearSession();
          }
          return throwError(() => err);
        }),
        finalize(() => (this.refreshInFlight = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refreshInFlight;
  }

  /** Au démarrage de l'application : rétablit la session si le cookie est valide. Ne rejette jamais. */
  restoreSession(): Promise<void> {
    return firstValueFrom(
      this.refresh().pipe(
        map(() => undefined),
        catchError(() => of(undefined)),
      ),
    );
  }

  /** Révoque le refresh token côté serveur ; la session locale est effacée même si l'appel échoue. */
  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(
      catchError(() => of(undefined)),
      map(() => undefined),
      finalize(() => this.clearSession()),
    );
  }

  clearSession(): void {
    this.token.set(null);
    this.currentUser.set(null);
  }

  private setSession(r: TokenResponse): void {
    this.token.set(r.accessToken);
    this.currentUser.set(r.user);
  }
}

/** URL de retour après connexion : uniquement un chemin interne (pas de redirection vers un autre site). */
export function safeReturnUrl(url: string | null | undefined): string {
  if (!url || !url.startsWith('/') || url.startsWith('//') || url.startsWith('/\\') || url.startsWith('/login')) {
    return '/';
  }
  return url;
}
