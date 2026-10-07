import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { LibraryPage } from './library';
import { AnimeSummary } from '../../core/api-types';
import { a11yViolations } from '../../core/a11y-testing';

const items = (from: number, n: number): AnimeSummary[] => Array.from({ length: n }, (_, i) =>
  ({ id: from + i, title: `Animé ${from + i}`, year: 2010, posterUrl: null, episodeCount: 12, lastAddedAt: null }));

describe('LibraryPage (P2.4)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LibraryPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(LibraryPage);
    for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
    await fixture.whenStable();
    http.expectOne('/api/genres').flush([{ genre: 'Comedy', label: 'Comédie', animeCount: 40 }]);
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('filtres de l’URL → paramètres de l’API (S2, S3, S6) ; compte des résultats ; accessible', async () => {
    const { fixture, el } = await open({ q: 'no', vu: 'en-cours', navigateur: '1', genre: 'Comedy', periode: '2010', tri: 'annee' });
    const req = http.expectOne((r) => r.url === '/api/anime');
    const p = req.request.params;
    expect([p.get('q'), p.get('watch'), p.get('browser'), p.get('genre'), p.get('yearFrom'), p.get('yearTo'), p.get('sort'), p.get('page')])
      .toEqual(['no', 'inProgress', 'true', 'Comedy', '2010', '2019', 'year', '0']);
    req.flush({ total: 2, page: 0, size: 60, items: items(1, 2) });
    await fixture.whenStable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('2 animés pour « no »');
    expect(el.querySelector('.chip[aria-pressed=true]')?.textContent).toContain('En cours');
    expect(el.querySelector<HTMLSelectElement>('#f-genre')!.value).toBe('Comedy');
    expect(el.querySelector('.clear-filters')).not.toBeNull();
    expect(el.querySelectorAll('.anime-card').length).toBe(2);
    expect(await a11yViolations(el)).toEqual([]);
  });

  it('un clic sur un filtre change l’URL et repart de la première page', async () => {
    const { fixture, el } = await open({ pages: 3 });
    // F5 après deux « Afficher plus » : les trois pages sont rechargées.
    for (let page = 0; page < 3; page++) {
      http.expectOne((r) => r.url === '/api/anime' && r.params.get('page') === String(page))
        .flush({ total: 200, page, size: 60, items: items(page * 60, 60) });
    }
    await fixture.whenStable();
    expect(el.querySelectorAll('.anime-card').length).toBe(180);
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigate');
    [...el.querySelectorAll<HTMLButtonElement>('.chip')].find((b) => b.textContent?.includes('Non vus'))!.click();
    expect(nav).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { vu: 'non-vus', pages: null } }));
  });

  it('« Afficher plus » ajoute la page suivante et l’écrit dans l’URL', async () => {
    const { fixture, el } = await open();
    http.expectOne((r) => r.url === '/api/anime').flush({ total: 70, page: 0, size: 60, items: items(1, 60) });
    await fixture.whenStable();
    expect(el.querySelector('.load-more')?.textContent).toContain('60 sur 70');
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate');
    el.querySelector<HTMLButtonElement>('.load-more button')!.click();
    http.expectOne((r) => r.url === '/api/anime' && r.params.get('page') === '1').flush({ total: 70, page: 1, size: 60, items: items(61, 10) });
    await fixture.whenStable();
    expect(el.querySelectorAll('.anime-card').length).toBe(70);
    expect(el.querySelector('.load-more')).toBeNull();
    expect(nav).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { pages: 2 }, replaceUrl: true }));
  });

  it('aucun résultat : message et « Tout effacer » ; erreur : « Réessayer »', async () => {
    const { fixture, el } = await open({ q: 'gundam', genre: 'Comedy' });
    http.expectOne((r) => r.url === '/api/anime').flush({ total: 0, page: 0, size: 60, items: [] });
    await fixture.whenStable();
    expect(el.querySelector('.state-title')?.textContent).toBe('Aucun animé pour « gundam »');
    expect(el.querySelector('.state-actions button')?.textContent).toContain('Tout effacer');

    const second = TestBed.createComponent(LibraryPage);
    await second.whenStable();
    http.expectOne('/api/genres').flush([]);
    http.expectOne((r) => r.url === '/api/anime').flush({ status: 500, error: 'X', message: 'Panne' }, { status: 500, statusText: 'KO' });
    await second.whenStable();
    expect((second.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain('Panne');
  });
});
