import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth.service';
import { tokens, USER, ADMIN } from './core/testing';
import { AccountPage } from './pages/account/account';
import { ThemeService } from './core/theme.service';
import { APP_NAME } from './core/app-name';
import { a11yViolations } from './core/a11y-testing';
import { SetupService } from './core/setup.service';

describe('Navigation (P2.2)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App, AccountPage],
      providers: [provideRouter([{ path: '**', children: [] }]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => localStorage.clear());

  function signIn(user = USER): void {
    TestBed.inject(AuthService).login('x', 'y').subscribe();
    http.expectOne('/api/auth/login').flush(tokens('t', user));
  }

  it('déconnecté : la marque seule, aucune navigation (et rien du tout sur /login)', async () => {
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/login');
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.brand')).toBeNull();
    await router.navigateByUrl('/a-propos');
    await fixture.whenStable();
    expect(el.querySelector('.brand')?.textContent).toContain(APP_NAME);
    expect(el.querySelector('.topnav')).toBeNull();
    expect(el.querySelector('.bottombar')).toBeNull();
  });

  it('connecté : barre du haut, barre du bas (4 onglets), menu Compte au clavier, accessible', async () => {
    signIn(ADMIN);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect([...el.querySelectorAll('.topnav a')].map((a) => a.textContent?.trim())).toEqual(['Accueil', 'Bibliothèque']);
    expect([...el.querySelectorAll('.bottombar .lbl')].map((a) => a.textContent)).toEqual(['Accueil', 'Rechercher', 'Bibliothèque', 'Compte']);
    expect(el.querySelector('form[role=search] input')).not.toBeNull();

    const trigger = el.querySelector<HTMLButtonElement>('.avatar-btn')!;
    expect(trigger.getAttribute('aria-label')).toBe('Compte : admin');
    trigger.click();
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.menu-panel')?.textContent).toContain('Administration');
    expect(await a11yViolations(el)).toEqual([]);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(el.querySelector('.menu-panel')).toBeNull();
  });

  it('version installée en bas à droite (connue par /api/setup/status), absente tant qu’elle n’est pas connue', async () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=app-version]')).toBeNull();
    TestBed.inject(SetupService).status.set({ installed: true, entry: 'public', adminExists: true, publicUrl: null, version: '1.0.1' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=app-version]')?.textContent?.trim()).toBe('Version 1.0.1');
  });

  it('menu d’un utilisateur : pas d’administration', async () => {
    signIn(USER);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('.avatar-btn')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.menu-panel')?.textContent).not.toContain('Administration');
  });

  it('Mon compte : thème de l’appareil, déconnexion', async () => {
    signIn(USER);
    const fixture = TestBed.createComponent(AccountPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toBe('alice');
    const light = [...el.querySelectorAll<HTMLButtonElement>('.theme-switch button')].find((b) => b.textContent?.includes('Clair'))!;
    light.click();
    await fixture.whenStable();
    expect(TestBed.inject(ThemeService).preference()).toBe('light');
    expect(light.getAttribute('aria-pressed')).toBe('true');
    expect(await a11yViolations(el)).toEqual([]);
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Se déconnecter'))!.click();
    http.expectOne('/api/auth/logout').flush(null);
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
  });
});

describe('Mon compte : changer son mot de passe (S4)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountPage],
      providers: [provideRouter([{ path: '**', children: [] }]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).login('x', 'y').subscribe();
    http.expectOne('/api/auth/login').flush(tokens('jeton-1', USER));
  });

  async function fill(current: string, next: string, confirm = next) {
    const fixture = TestBed.createComponent(AccountPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    for (const [id, v] of [['current-password', current], ['new-password', next], ['confirm-password', confirm]]) {
      const input = el.querySelector<HTMLInputElement>('#' + id)!;
      input.value = v;
      input.dispatchEvent(new Event('input'));
    }
    el.querySelector('.password-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    return { fixture, el };
  }

  it('envoie l’ancien et le nouveau avec le jeton d’accès ; annonce les sessions fermées', async () => {
    const { fixture, el } = await fill('ancien-mot-de-passe', 'nouveau-mot-de-passe');
    const req = http.expectOne('/api/auth/password');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jeton-1');
    expect(req.request.body).toEqual({ currentPassword: 'ancien-mot-de-passe', newPassword: 'nouveau-mot-de-passe' });
    req.flush({ closedSessions: 2 });
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Mot de passe changé. 2 autres sessions ont été fermées.');
    expect(el.querySelector<HTMLInputElement>('#new-password')!.value).toBe('');
  });

  it('vérifications locales : longueur et confirmation ; mot de passe actuel faux', async () => {
    const short = await fill('ancien-mot-de-passe', 'court');
    expect(short.el.querySelector('[role=alert]')?.textContent).toContain('au moins 10 caractères');
    const mismatch = await fill('ancien-mot-de-passe', 'nouveau-mot-de-passe', 'autre-mot-de-passe');
    expect(mismatch.el.querySelector('[role=alert]')?.textContent).toContain('pas identiques');
    http.expectNone('/api/auth/password');
    const wrong = await fill('faux-mot-de-passe', 'nouveau-mot-de-passe');
    http.expectOne('/api/auth/password').flush({ status: 400, error: 'WRONG_PASSWORD', message: 'x' }, { status: 400, statusText: 'Bad' });
    await wrong.fixture.whenStable();
    expect(wrong.el.querySelector('[role=alert]')?.textContent).toContain('Le mot de passe actuel est incorrect.');
    expect(await a11yViolations(wrong.el)).toEqual([]);
  });

  it('jeton expiré : rafraîchi une fois puis la demande est rejouée', async () => {
    await fill('ancien-mot-de-passe', 'nouveau-mot-de-passe');
    http.expectOne('/api/auth/password').flush({ status: 401 }, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/refresh').flush(tokens('jeton-2', USER));
    const retry = http.expectOne('/api/auth/password');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer jeton-2');
    retry.flush({ closedSessions: 0 });
  });
});
