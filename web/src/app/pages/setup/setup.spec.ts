import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { SetupPage } from './setup';
import { SetupService, SetupStatus } from '../../core/setup.service';
import { installationGuard, setupGuard } from '../../core/guards';
import { tokens } from '../../core/testing';

const BASE: SetupStatus = { installed: false, entry: 'lan', adminExists: false, publicUrl: 'https://anime.duckdns.org', version: '1.0.0' };

describe('Installation (assistant de premier lancement)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SetupPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function withStatus(s: Partial<SetupStatus>): void {
    TestBed.inject(SetupService).status.set({ ...BASE, ...s });
  }

  async function render() {
    const fixture = TestBed.createComponent(SetupPage);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function guard(g: typeof setupGuard): boolean | string {
    const r = TestBed.runInInjectionContext(() => g({} as ActivatedRouteSnapshot, { url: '/' } as RouterStateSnapshot)) as boolean | UrlTree;
    return r instanceof UrlTree ? TestBed.inject(Router).serializeUrl(r) : r;
  }

  it('gardes : tant que l’installation n’est pas terminée, tout mène à /installation', () => {
    withStatus({});
    expect(guard(setupGuard)).toBe('/installation');
    expect(guard(installationGuard)).toBe(true);
    withStatus({ installed: true, entry: 'public' });
    expect(guard(setupGuard)).toBe(true);
    expect(guard(installationGuard)).toBe('/');
    withStatus({ installed: true, entry: 'lan' }); // adresse locale après l'installation : la page explicative
    expect(guard(setupGuard)).toBe('/installation');
  });

  it('par Internet pendant l’installation : « installation en cours », aucun formulaire', async () => {
    withStatus({ entry: 'public' });
    const { el } = await render();
    expect(el.querySelector('h1')!.textContent).toContain('Installation en cours');
    expect(el.querySelector('form')).toBeNull();
    http.expectNone('/api/setup/checks');
  });

  it('adresse locale après l’installation : lien vers l’adresse publique', async () => {
    withStatus({ installed: true, entry: 'lan' });
    const { el } = await render();
    expect(el.querySelector<HTMLAnchorElement>('.big-link a')!.href).toBe('https://anime.duckdns.org/');
  });

  it('vérifications, puis compte administrateur choisi par la personne (deux saisies identiques exigées)', async () => {
    withStatus({});
    const { fixture, el } = await render();
    http.expectOne('/api/setup/checks').flush([
      { id: 'media', label: 'Dossier des vidéos', state: 'FAIL', detail: 'Illisible.', fix: 'Donner la lecture.' },
      { id: 'disk', label: 'Espace disque', state: 'OK', detail: '500 Go libres.', fix: null },
    ]);
    await fixture.whenStable();
    expect(el.textContent).toContain('À corriger');
    expect(el.textContent).toContain('Donner la lecture.');
    el.querySelector<HTMLButtonElement>('.setup-actions .btn-primary')!.click();
    await fixture.whenStable();

    type(el, '#a-name', 'matthieu');
    type(el, '#a-pass', 'un-mot-de-passe-long');
    type(el, '#a-pass2', 'un-autre-mot-de-passe');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')!.textContent).toContain('différents');
    http.expectNone('/api/setup/admin');

    type(el, '#a-pass2', 'un-mot-de-passe-long');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/setup/admin');
    expect(req.request.body).toEqual({ username: 'matthieu', password: 'un-mot-de-passe-long' });
    req.flush(tokens('jeton-assistant'));
    // Étape suivante : espace disque, avec le jeton de l'assistant.
    const disk = http.expectOne('/api/setup/disk');
    expect(disk.request.headers.get('Authorization')).toBe('Bearer jeton-assistant');
    disk.flush({ totalBytes: 4e12, freeBytes: 5e11, current: { warnGb: 50, criticalGb: 20, remuxCapGb: 50 },
      proposed: { warnGb: 50, criticalGb: 20, remuxCapGb: 75 }, saved: false });
    await fixture.whenStable();
    expect(el.querySelector<HTMLInputElement>('#d-cap')!.value).toBe('75'); // proposition selon l'espace libre
    expect(el.textContent).toContain('500 Go');
  });

  it('espace disque : cache du lecteur web montré (dossier, place) et plafond enregistré', async () => {
    withStatus({ adminExists: true });
    const { fixture, el } = await render();
    http.expectOne('/api/setup/checks').flush([]);
    (fixture.componentInstance as unknown as { go(s: string): void }).go('disk');
    await fixture.whenStable();
    http.expectOne('/api/setup/disk').flush({ totalBytes: 4e12, freeBytes: 5e11, saved: false,
      current: { warnGb: 50, criticalGb: 20, remuxCapGb: 50, webCapGb: 200 },
      proposed: { warnGb: 50, criticalGb: 20, remuxCapGb: 75, webCapGb: 200 },
      webCache: { hostPath: '/volume2/anime-cache', totalBytes: 2e12, freeBytes: 1.5e12, usedBytes: 0 } });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=web-cache]')!.textContent).toContain('/volume2/anime-cache');
    expect(el.querySelector<HTMLInputElement>('#d-web')!.value).toBe('200');
    type(el, '#d-web', '120');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    const put = http.expectOne((r) => r.url === '/api/setup/disk' && r.method === 'PUT');
    expect(put.request.body.webCapGb).toBe(120);
  });

  it('fin de l’installation : le site s’ouvre, l’assistant se ferme', async () => {
    withStatus({ adminExists: true });
    const { fixture, el } = await render();
    http.expectOne('/api/setup/checks').flush([]);
    const page = fixture.componentInstance as unknown as { go(s: string): void };
    page.go('finish');
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.btn-lg')!.click();
    http.expectOne('/api/setup/finish').flush({ ...BASE, installed: true, adminExists: true });
    await fixture.whenStable();
    expect(el.querySelector('h1')!.textContent).toContain('Installation terminée');
  });
});
