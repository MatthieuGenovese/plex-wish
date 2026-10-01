import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { tokens } from './testing';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    auth.login('admin', 'pw').subscribe();
    backend.expectOne('/api/auth/login').flush(tokens('old'));
  });

  afterEach(() => backend.verify());

  const unauthorized = { status: 401, statusText: 'Unauthorized' };

  it('ajoute le token d’accès aux appels de l’API', () => {
    http.get('/api/anime').subscribe();
    expect(backend.expectOne('/api/anime').request.headers.get('Authorization')).toBe('Bearer old');
  });

  it('n’ajoute rien aux appels d’authentification ni hors API', () => {
    http.post('/api/auth/refresh', null).subscribe();
    expect(backend.expectOne('/api/auth/refresh').request.headers.has('Authorization')).toBe(false);
    http.get('/assets/x.json').subscribe();
    expect(backend.expectOne('/assets/x.json').request.headers.has('Authorization')).toBe(false);
  });

  it('sur un 401 : rafraîchit puis rejoue la requête avec le nouveau token', () => {
    let body: unknown;
    http.get('/api/anime').subscribe((b) => (body = b));
    backend.expectOne('/api/anime').flush({}, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(tokens('new'));
    const retry = backend.expectOne('/api/anime');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer new');
    retry.flush({ items: [] });
    expect(body).toEqual({ items: [] });
  });

  it('plusieurs 401 simultanés : un seul refresh, toutes les requêtes rejouées', () => {
    const done: string[] = [];
    for (const url of ['/api/anime', '/api/me', '/api/anime/1']) {
      http.get(url).subscribe(() => done.push(url));
    }
    for (const req of backend.match((r) => r.url !== '/api/auth/refresh')) {
      req.flush({}, unauthorized);
    }
    const refreshes = backend.match('/api/auth/refresh');
    expect(refreshes.length).toBe(1);
    refreshes[0].flush(tokens('new'));

    const retries = backend.match((r) => r.url !== '/api/auth/refresh');
    expect(retries.length).toBe(3);
    retries.forEach((r) => {
      expect(r.request.headers.get('Authorization')).toBe('Bearer new');
      r.flush({});
    });
    expect(done.sort()).toEqual(['/api/anime', '/api/anime/1', '/api/me']);
  });

  it('un 401 reçu après un refresh déjà terminé rejoue avec le token courant, sans nouveau refresh', () => {
    http.get('/api/a').subscribe();
    http.get('/api/b').subscribe();
    backend.expectOne('/api/a').flush({}, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(tokens('new'));
    backend.expectOne('/api/a').flush({});
    // La réponse de /api/b (envoyée avec l'ancien token) arrive après le refresh.
    backend.expectOne('/api/b').flush({}, unauthorized);
    backend.expectNone('/api/auth/refresh');
    expect(backend.expectOne('/api/b').request.headers.get('Authorization')).toBe('Bearer new');
  });

  it('ne rejoue qu’une fois : un deuxième 401 est renvoyé à l’appelant', () => {
    let status = 0;
    http.get('/api/anime').subscribe({ error: (e) => (status = e.status) });
    backend.expectOne('/api/anime').flush({}, unauthorized);
    backend.expectOne('/api/auth/refresh').flush(tokens('new'));
    backend.expectOne('/api/anime').flush({}, unauthorized);
    backend.expectNone('/api/auth/refresh');
    expect(status).toBe(401);
  });

  it('session expirée : retour à la connexion avec la page demandée', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    let status = 0;
    http.get('/api/anime').subscribe({ error: (e) => (status = e.status) });
    backend.expectOne('/api/anime').flush({}, unauthorized);
    backend.expectOne('/api/auth/refresh').flush({ error: 'INVALID_REFRESH_TOKEN' }, unauthorized);
    expect(status).toBe(401);
    expect(auth.isLoggedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });

  it('les autres erreurs passent sans refresh', () => {
    let status = 0;
    http.get('/api/anime').subscribe({ error: (e) => (status = e.status) });
    backend.expectOne('/api/anime').flush({}, { status: 403, statusText: 'Forbidden' });
    backend.expectNone('/api/auth/refresh');
    expect(status).toBe(403);
  });
});
