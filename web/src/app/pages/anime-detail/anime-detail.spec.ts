import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AnimeDetailPage, chunksOf } from './anime-detail';
import { AnimeDetail, EpisodeSummary, Progress, Resume } from '../../core/api-types';
import { a11yViolations } from '../../core/a11y-testing';

const episodes = (n: number, from = 1): EpisodeSummary[] =>
  Array.from({ length: n }, (_, i) => ({ id: from + i, episodeNumber: i + 1, title: null, durationSeconds: 1440 }));

const detail = (extra: Partial<AnimeDetail> = {}): AnimeDetail => ({
  id: 7, title: 'One Piece', alternativeTitle: null, synopsis: null, synopsisLanguage: null, posterUrl: null,
  posterLargeUrl: null, year: 1999, metadataSource: null, metadataUrl: null, synopsisSource: null, frenchTitle: null,
  tmdbUrl: null,
  seasons: [
    { id: 70, seasonNumber: 1, label: 'Saison 1', episodeCount: 250 },
    { id: 71, seasonNumber: 0, label: 'Spéciaux', episodeCount: 2 },
  ],
  resume: null, genres: [], ...extra,
});

describe('AnimeDetailPage (P2.5)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AnimeDetailPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function open(anime: AnimeDetail, inputs: Record<string, unknown> = {}, progress: Progress[] = []) {
    const fixture = TestBed.createComponent(AnimeDetailPage);
    fixture.componentRef.setInput('id', String(anime.id));
    for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
    await fixture.whenStable();
    http.expectOne(`/api/anime/${anime.id}`).flush(anime);
    http.expectOne((r) => r.url === '/api/me/progress').flush(progress);
    await fixture.whenStable();
    http.match((r) => r.url.endsWith('/cast')).forEach((r) => r.flush({ source: null, sourceUrl: null, items: [] }));
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const text = (el: HTMLElement) => el.textContent?.replace(/\s+/g, ' ') ?? '';

  it('héros : titres, année, saisons et épisodes, genres cliquables ; encadré « Pour commencer » ; accessible', async () => {
    const { fixture, el } = await open(detail({
      frenchTitle: 'One Piece (VF)', genres: [{ genre: 'Adventure', label: 'Aventure' }],
      resume: { kind: 'START', episodeId: 1, seasonId: 70, seasonNumber: 1, episodeNumber: 1, episodeTitle: null, positionSeconds: 0, durationSeconds: 1440 },
    }));
    http.expectOne('/api/seasons/70/episodes').flush(episodes(250));
    await fixture.whenStable();
    expect(el.querySelector('h1')?.textContent).toBe('One Piece');
    expect(el.querySelector('.hero-sub')?.textContent).toBe('One Piece (VF)');
    expect(el.querySelector('.hero-meta')?.textContent).toBe('1999 · 252 épisodes');
    expect(el.querySelector('.genre-chips a')?.getAttribute('href')).toBe('/anime?genre=Adventure');
    expect(el.querySelector('.resume-label')?.textContent).toBe('Pour commencer');
    expect(el.querySelector('.resume-box a[data-testid=hero-play]')?.textContent).toContain('Regarder le premier épisode');
    expect(el.querySelector('.resume-box a[data-testid=hero-play]')?.getAttribute('href')).toBe('/regarder/1');
    // Chaque épisode ouvre le lecteur web.
    expect(el.querySelector('.episode a.ep-link')?.getAttribute('href')).toBe('/regarder/1');
    // Saisons en boutons, 250 épisodes : menu de plages, 100 lignes affichées.
    expect([...el.querySelectorAll('.seasons a')].map((a) => a.textContent?.trim())).toEqual(['Saison 1', 'Spéciaux']);
    expect([...el.querySelectorAll('#chunk-select option')].map((o) => o.textContent?.trim()))
      .toEqual(['Épisodes 1–100', 'Épisodes 101–200', 'Épisodes 201–250']);
    expect(el.querySelectorAll('.episode').length).toBe(100);
    expect(await a11yViolations(el)).toEqual([]);
  });

  it('reprise (S5) : bonne saison et bonne plage ouvertes, épisode en évidence, états vu / en cours', async () => {
    const resume: Resume = { kind: 'RESUME', episodeId: 150, seasonId: 70, seasonNumber: 1, episodeNumber: 150,
      episodeTitle: 'Le marché de nuit', positionSeconds: 600, durationSeconds: 1440 };
    const { fixture, el } = await open(detail({ resume }), { episode: 150 }, [
      { episodeId: 149, positionSeconds: 1440, durationSeconds: 1440, completed: true, updatedAt: null },
      { episodeId: 150, positionSeconds: 600, durationSeconds: 1440, completed: false, updatedAt: null },
    ]);
    http.expectOne('/api/seasons/70/episodes').flush(episodes(250));
    await fixture.whenStable();
    expect(el.querySelector('.resume-label')?.textContent).toBe('Vous en êtes à');
    expect(text(el.querySelector('.resume-ep')!)).toContain('Saison 1 · Épisode 150 · Le marché de nuit');
    expect(el.querySelector('.resume-box .hero-progress')?.textContent).toContain('reste 14 min');
    expect(el.querySelector<HTMLSelectElement>('#chunk-select')!.value).toBe('1'); // plage 101–200
    const target = el.querySelector('#ep-150')!;
    expect(target.classList).toContain('is-target');
    expect(target.textContent).toContain('reste 14 min');
    expect(el.querySelector('#ep-149')?.classList).toContain('is-seen');
    expect(el.querySelector('#ep-149')?.textContent).toContain('Vu');
    expect(text(el.querySelector('.episodes-count')!)).toBe('250 épisodes · 1 vu');

    const nav = vi.spyOn(TestBed.inject(Router), 'navigate');
    el.querySelector<HTMLButtonElement>('.resume-box button')!.click();
    expect(nav).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { saison: 1, episode: 150 } }));
  });

  it('épisode suivant et « tout vu »', async () => {
    const next = await open(detail({ resume: { kind: 'NEXT', episodeId: 2, seasonId: 71, seasonNumber: 0, episodeNumber: 2,
      episodeTitle: null, positionSeconds: 0, durationSeconds: 0 } }));
    // La saison de l'épisode suivant (Spéciaux) est ouverte d'office.
    http.expectOne('/api/seasons/71/episodes').flush(episodes(2));
    await next.fixture.whenStable();
    expect(next.el.querySelector('.resume-label')?.textContent).toBe('Prochain épisode');
    expect(text(next.el.querySelector('.resume-ep')!)).toBe('Spéciaux · Épisode 2');
    expect(next.el.querySelector('.resume-box .hero-progress')).toBeNull();
    expect(next.el.querySelector('#chunk-select')).toBeNull();
  });

  it('format non lisible dans un navigateur : signalé par épisode et pour la saison', async () => {
    const { fixture, el } = await open(detail(), { saison: 0 });
    const list = episodes(2);
    list[0] = { ...list[0], browserPlayable: false };
    list[1] = { ...list[1], browserPlayable: true };
    http.expectOne('/api/seasons/71/episodes').flush(list);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=browser-note]')?.textContent).toContain('1 épisode est dans un format que le navigateur ne lit pas toujours');
    expect(el.querySelector('[data-testid=browser-note]')?.textContent).toContain('application Android');
    expect(el.querySelectorAll('.episode')[0].textContent).toContain('Android conseillé');
    expect(el.querySelectorAll('.episode')[1].textContent).not.toContain('Android conseillé');
  });

  it('toute la saison illisible dans un navigateur : la note suffit, pas de badge sur chaque épisode', async () => {
    const { fixture, el } = await open(detail(), { saison: 0 });
    http.expectOne('/api/seasons/71/episodes').flush(episodes(2).map((e) => ({ ...e, browserPlayable: false })));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=browser-note]')?.textContent).toContain('Les épisodes de cette saison sont');
    expect(el.querySelector('.episodes')?.textContent).not.toContain('Android conseillé');
  });

  it('synopsis long replié avec « Lire la suite », langue et sources', async () => {
    const { fixture, el } = await open(detail({
      id: 8, synopsis: 'Ligne.\n\n' + 'Très long synopsis. '.repeat(20), synopsisLanguage: 'en',
      metadataSource: 'AniList', metadataUrl: 'https://anilist.co/anime/154587', tmdbUrl: 'https://www.themoviedb.org/tv/209867',
      seasons: [{ id: 80, seasonNumber: 1, label: 'Saison 1', episodeCount: 1 }],
    }));
    http.expectOne('/api/seasons/80/episodes').flush(episodes(1));
    await fixture.whenStable();
    const syn = el.querySelector('.synopsis')!;
    expect(syn.getAttribute('lang')).toBe('en');
    expect(syn.classList).toContain('clamped');
    const more = el.querySelector<HTMLButtonElement>('.synopsis-block button')!;
    expect(more.textContent).toContain('Lire la suite');
    more.click();
    await fixture.whenStable();
    expect(syn.classList).not.toContain('clamped');
    expect(more.getAttribute('aria-expanded')).toBe('true');
    expect(text(el.querySelector('.source')!)).toContain('Synopsis en anglais (pas de traduction française) · Sources : AniList · TMDB');
    expect(el.querySelector('.seasons')).toBeNull(); // une seule saison : pas de sélecteur
  });

  it('sans affiche : couverture composée ; animé introuvable : message', async () => {
    const { fixture, el } = await open(detail({ seasons: [{ id: 70, seasonNumber: 1, label: 'Saison 1', episodeCount: 1 }] }));
    http.expectOne('/api/seasons/70/episodes').flush(episodes(1));
    await fixture.whenStable();
    expect(el.querySelector('.hero-poster [data-testid=poster-placeholder]')?.textContent).toBe('One Piece');

    const missing = TestBed.createComponent(AnimeDetailPage);
    missing.componentRef.setInput('id', '404');
    await missing.whenStable();
    http.expectOne('/api/anime/404').flush({ status: 404, error: 'ANIME_NOT_FOUND', message: 'Animé introuvable' }, { status: 404, statusText: 'NF' });
    http.expectOne((r) => r.url === '/api/me/progress').flush([]);
    await missing.whenStable();
    expect((missing.nativeElement as HTMLElement).textContent).toContain('n’existe pas');
  });

  it('découpe en plages seulement au-delà de 100 épisodes', () => {
    expect(chunksOf(episodes(100))).toEqual([]);
    expect(chunksOf(episodes(101)).map((c) => c.label)).toEqual(['1–100', '101–101']);
  });
});
