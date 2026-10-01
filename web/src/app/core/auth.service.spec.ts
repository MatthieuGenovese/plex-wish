import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService, safeReturnUrl } from './auth.service';
import { User } from './api-types';
import { USER, tokens } from './testing';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('garde le token en mémoire après la connexion, sans rien écrire dans le stockage du navigateur', () => {
    let user: User | undefined;
    auth.login('admin', 'secret').subscribe((u) => (user = u));
    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ login: 'admin', password: 'secret' });
    req.flush(tokens('t1'));

    expect(user?.username).toBe('admin');
    expect(auth.accessToken()).toBe('t1');
    expect(auth.isLoggedIn()).toBe(true);
    expect(auth.isAdmin()).toBe(true);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('rétablit la session au démarrage grâce au cookie (F5 ne déconnecte pas)', async () => {
    const restored = auth.restoreSession();
    http.expectOne('/api/auth/refresh').flush(tokens('t2', USER));
    await restored;
    expect(auth.accessToken()).toBe('t2');
    expect(auth.user()?.username).toBe('alice');
    expect(auth.isAdmin()).toBe(false);
  });

  it('démarre déconnecté si le cookie est absent ou expiré, sans erreur', async () => {
    const restored = auth.restoreSession();
    http.expectOne('/api/auth/refresh').flush({ error: 'INVALID_REFRESH_TOKEN' }, { status: 401, statusText: 'Unauthorized' });
    await expect(restored).resolves.toBeUndefined();
    expect(auth.isLoggedIn()).toBe(false);
  });

  it('démarre aussi si le serveur est injoignable', async () => {
    const restored = auth.restoreSession();
    http.expectOne('/api/auth/refresh').error(new ProgressEvent('error'));
    await expect(restored).resolves.toBeUndefined();
    expect(auth.isLoggedIn()).toBe(false);
  });

  it('partage un refresh en cours : un seul appel réseau pour plusieurs demandes', () => {
    const results: string[] = [];
    auth.refresh().subscribe((t) => results.push(t));
    auth.refresh().subscribe((t) => results.push(t));
    auth.refresh().subscribe((t) => results.push(t));
    http.expectOne('/api/auth/refresh').flush(tokens('t3'));
    expect(results).toEqual(['t3', 't3', 't3']);

    // Le refresh suivant est un nouvel appel.
    auth.refresh().subscribe();
    http.expectOne('/api/auth/refresh').flush(tokens('t4'));
    expect(auth.accessToken()).toBe('t4');
  });

  it('un refresh refusé (401) efface la session', () => {
    auth.login('admin', 'secret').subscribe();
    http.expectOne('/api/auth/login').flush(tokens('t1'));
    auth.refresh().subscribe({ error: () => undefined });
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accessToken()).toBeNull();
    expect(auth.isLoggedIn()).toBe(false);
  });

  it('la déconnexion révoque côté serveur et efface la session, même si l’appel échoue', () => {
    auth.login('admin', 'secret').subscribe();
    http.expectOne('/api/auth/login').flush(tokens('t1'));
    let done = false;
    auth.logout().subscribe({ complete: () => (done = true) });
    http.expectOne('/api/auth/logout').flush('boom', { status: 500, statusText: 'Error' });
    expect(done).toBe(true);
    expect(auth.isLoggedIn()).toBe(false);
  });

  it('n’accepte que des URL de retour internes', () => {
    expect(safeReturnUrl('/anime/12?saison=2')).toBe('/anime/12?saison=2');
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('//evil.example')).toBe('/');
    expect(safeReturnUrl('/\\evil.example')).toBe('/');
    expect(safeReturnUrl('/login')).toBe('/');
    expect(safeReturnUrl(undefined)).toBe('/');
  });
});
