import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { ADMIN, USER, tokens } from './testing';
import { adminGuard, authGuard, guestGuard } from './guards';
import { User } from './api-types';

describe('gardes de routes', () => {
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    router = TestBed.inject(Router);
  });

  function loginAs(user: User): void {
    TestBed.inject(AuthService).login(user.username, 'pw').subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/auth/login').flush(tokens('t', user));
  }

  function run(guard: typeof authGuard, url: string): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  const serialize = (r: boolean | UrlTree) => (r instanceof UrlTree ? router.serializeUrl(r) : r);

  it('authGuard : anonyme → /login avec la page demandée', () => {
    expect(serialize(run(authGuard, '/anime/12'))).toBe('/login?returnUrl=%2Fanime%2F12');
  });

  it('authGuard : connecté → accès', () => {
    loginAs(USER);
    expect(run(authGuard, '/anime')).toBe(true);
  });

  it('adminGuard : anonyme → /login', () => {
    expect(serialize(run(adminGuard, '/admin/users'))).toBe('/login?returnUrl=%2Fadmin%2Fusers');
  });

  it('adminGuard : utilisateur normal → accueil', () => {
    loginAs(USER);
    expect(serialize(run(adminGuard, '/admin'))).toBe('/');
  });

  it('adminGuard : admin → accès', () => {
    loginAs(ADMIN);
    expect(run(adminGuard, '/admin')).toBe(true);
  });

  it('guestGuard : la page de connexion renvoie un utilisateur connecté à l’accueil', () => {
    expect(run(guestGuard, '/login')).toBe(true);
    loginAs(USER);
    expect(serialize(run(guestGuard, '/login'))).toBe('/');
  });
});
