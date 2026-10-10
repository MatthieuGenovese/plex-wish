import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CastPage } from './cast';

const summary = {
  enabled: true, running: true, folderUsable: true, maxRoles: 20, withAniList: 1247,
  counts: { OK: 300, PENDING: 940, NONE: 2, FAILED: 1, EXCLUDED: 4 }, people: 1800, characters: 5600, roles: 5900,
  images: { OK: 7000, PENDING: 300, FAILED: 3 }, diskBytes: 250_000_000, estimatedBytes: 1_010_000_000,
  waitingForMetadata: true, pausedUntil: null, lastUnavailable: null, nextCheckAt: null,
};

describe('CastPage (admin)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CastPage], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('« en attente » expliqué : heure du prochain passage', async () => {
    const fixture = TestBed.createComponent(CastPage);
    await fixture.whenStable();
    http.expectOne('/api/admin/cast/summary').flush({ ...summary, waitingForMetadata: false, nextCheckAt: '2026-10-10T12:32:00Z' });
    http.expectOne((r) => r.url === '/api/admin/cast').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).querySelector('[data-testid=cast-status]')?.textContent ?? '';
    expect(text).toContain('940 en attente');
    expect(text).toContain('prochain passage vers');
  });

  it('avancement, priorité aux métadonnées, place disque, relance, effacement confirmé', async () => {
    const fixture = TestBed.createComponent(CastPage);
    fixture.componentRef.setInput('filtre', 'missing');
    await fixture.whenStable();
    http.expectOne('/api/admin/cast/summary').flush(summary);
    const list = http.expectOne((r) => r.url === '/api/admin/cast');
    expect(list.request.params.get('filter')).toBe('missing');
    list.flush({ total: 2, page: 0, size: 50, items: [
      { animeId: 5, title: 'Gamma', status: 'FAILED', roles: 0, seasons: 0, fetchedAt: null, lastError: 'fiche AniList introuvable' },
      { animeId: 6, title: 'NoMatch', status: 'NO_MATCH', roles: 0, seasons: 0, fetchedAt: null, lastError: null },
    ] });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=cast-status]')?.textContent).toContain('dès qu’elles sont terminées');
    expect(el.querySelector('[data-testid=cast-disk]')?.textContent).toContain('250 Mo utilisés');
    const rows = el.querySelectorAll('tbody tr');
    expect(rows[1].querySelector('button')).toBeNull(); // pas d'appariement : rien à relancer

    rows[0].querySelector('button')!.click();
    http.expectOne('/api/admin/anime/5/cast/refresh').flush({ queued: true, running: true });
    await fixture.whenStable();
    http.expectOne('/api/admin/cast/summary').flush(summary);
    http.expectOne((r) => r.url === '/api/admin/cast').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect(el.textContent).toContain('« Gamma » : distribution remise en file');

    el.querySelector<HTMLButtonElement>('.purge .btn-danger')!.click();
    await fixture.whenStable();
    el.querySelectorAll<HTMLButtonElement>('.purge .btn-danger')[0].click();
    const purge = http.expectOne((r) => r.url === '/api/admin/cast/purge');
    expect(purge.request.params.get('confirm')).toBe('true');
    purge.flush({ purged: 5900, running: true });
    await fixture.whenStable();
    http.expectOne('/api/admin/cast/summary').flush(summary);
    http.expectOne((r) => r.url === '/api/admin/cast').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect(el.textContent).toContain('Distribution effacée');
  });
});
