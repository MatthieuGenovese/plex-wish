import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { HomePage } from './home';
import { AnimeSummary, ContinueWatching } from '../../core/api-types';
import { AuthService } from '../../core/auth.service';
import { ADMIN, tokens } from '../../core/testing';
import { a11yViolations } from '../../core/a11y-testing';

const anime = (id: number, title = `Animé ${id}`): AnimeSummary =>
  ({ id, title, year: 2020, posterUrl: null, episodeCount: 12, lastAddedAt: '2026-01-01T00:00:00Z' });
const cw = (kind: 'RESUME' | 'NEXT', animeId: number, extra: Partial<ContinueWatching> = {}): ContinueWatching => ({
  kind, episodeId: animeId * 10, episodeNumber: 4, episodeTitle: 'Le marché de nuit', seasonId: 1, seasonNumber: 1,
  seasonLabel: 'Saison 1', animeId, animeTitle: `Série ${animeId}`, positionSeconds: kind === 'RESUME' ? 600 : 0,
  durationSeconds: 1440, updatedAt: null, posterUrl: null, ...extra,
});

describe('HomePage (P2.3)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(continueWatching: ContinueWatching[], total: number, items: AnimeSummary[]) {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/me/continue-watching').flush(continueWatching);
    const recent = http.expectOne((r) => r.url === '/api/anime' && r.params.get('sort') === 'recent' && !r.params.has('genre'));
    recent.flush({ total, page: 0, size: 20, items });
    http.expectOne('/api/genres').flush(total ? [
      { genre: 'Comedy', label: 'Comédie', animeCount: 40 }, { genre: 'Hentai', label: 'Hentai', animeCount: 90 },
      { genre: 'Drama', label: 'Drame', animeCount: 2 },
    ] : []);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  function flushRows(discover: boolean): void {
    if (discover) {
      http.expectOne((r: { url: string; params: { get(k: string): string | null } }) => r.url === '/api/anime' && r.params.get('sort') === 'title')
        .flush({ total: 300, page: 3, size: 20, items: [anime(90), anime(91)] });
    }
    // Rangées de genres : seulement Comédie (Hentai jamais à l'accueil, Drame trop petit).
    const g: TestRequest = http.expectOne((r) => r.params.get('genre') === 'Comedy');
    g.flush({ total: 40, page: 0, size: 20, items: [anime(50, 'Comédie 1')] });
  }

  it('héros « À reprendre » avec le temps restant, rangées, lien vers l’épisode ; accessible', async () => {
    const { fixture, el } = await open([cw('RESUME', 7), cw('NEXT', 8)], 300, [anime(1, 'Tout juste ajouté'), anime(2)]);
    flushRows(true);
    await fixture.whenStable();
    expect(el.querySelector('.hero-kicker')?.textContent).toBe('À reprendre');
    expect(el.querySelector('.hero-title')?.textContent).toBe('Série 7');
    expect(el.querySelector('.hero-meta')?.textContent).toContain('Saison 1 · Épisode 4 · Le marché de nuit');
    expect(el.querySelector('.hero-progress')?.textContent).toContain('reste 14 min');
    const cta = el.querySelector<HTMLAnchorElement>('.hero-actions a')!;
    expect(cta.getAttribute('href')).toBe('/regarder/70'); // lecture directe dans le lecteur web
    // La rangée montre les épisodes suivants (le premier est dans le héros).
    expect([...el.querySelectorAll('#rail-continue ~ * .resume-title, .resume-title')].map((t) => t.textContent)).toEqual(['Série 8']);
    expect(el.querySelector('.resume-kicker')?.textContent).toBe('Épisode suivant');
    expect([...el.querySelectorAll('.rail-head h2')].map((h) => h.textContent)).toEqual(
      ['Continuer à regarder', 'Récemment ajoutés', 'Comédie', 'À découvrir']);
    expect(el.querySelector('a.rail-more[href="/anime?genre=Comedy"]')).not.toBeNull();
    expect(await a11yViolations(el)).toEqual([]);
  });

  it('épisode suivant en héros ; sans historique, le dernier ajout', async () => {
    const next = await open([cw('NEXT', 8)], 5, [anime(1, 'Tout juste ajouté')]);
    flushRows(false);
    await next.fixture.whenStable();
    expect(next.el.querySelector('.hero-kicker')?.textContent).toBe('À suivre');
    expect(next.el.querySelector('.hero-progress')).toBeNull();
    expect(next.el.querySelector('#rail-continue')).toBeNull(); // une seule entrée : le héros suffit
  });

  it('nouveau compte : le dernier ajout en héros, pas de « Continuer à regarder »', async () => {
    const { fixture, el } = await open([], 5, [anime(1, 'Tout juste ajouté')]);
    flushRows(false);
    await fixture.whenStable();
    expect(el.querySelector('.hero-kicker')?.textContent).toBe('Dernier ajout');
    expect(el.querySelector('.hero-title')?.textContent).toBe('Tout juste ajouté');
    expect(el.querySelector('.hero-hint')).toBeNull();
    expect(el.textContent).not.toContain('Continuer à regarder');
  });

  it('bibliothèque vide : état explicite, lien de scan pour l’admin', async () => {
    TestBed.inject(AuthService).login('a', 'b').subscribe();
    http.expectOne('/api/auth/login').flush(tokens('t', ADMIN));
    const { el } = await open([], 0, []);
    expect(el.querySelector('.state-title')?.textContent).toBe('La bibliothèque est vide');
    expect(el.querySelector('a[href="/admin/scan"]')).not.toBeNull();
    expect(await a11yViolations(el)).toEqual([]);
  });

  it('serveur injoignable : erreur et « Réessayer »', async () => {
    const fixture = TestBed.createComponent(HomePage);
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/me/continue-watching').flush([]);
    http.expectOne('/api/genres').flush([]);
    http.expectOne((r) => r.url === '/api/anime').error(new ProgressEvent('error'), { status: 0 });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Serveur injoignable');
    expect(el.querySelector('button')?.textContent).toContain('Réessayer');
  });
});
