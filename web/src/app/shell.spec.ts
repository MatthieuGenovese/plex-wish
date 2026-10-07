import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth.service';
import { tokens, USER, ADMIN } from './core/testing';
import { AccountPage } from './pages/account/account';
import { ThemeService } from './core/theme.service';
import { APP_NAME } from './core/app-name';
import { a11yViolations } from './core/a11y-testing';

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

  it('déconnecté : la marque seule, aucune navigation', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
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
