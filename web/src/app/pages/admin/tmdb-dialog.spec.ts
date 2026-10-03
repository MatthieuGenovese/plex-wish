import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TmdbEntry } from '../../core/api-types';
import { parseTmdbRef, TmdbDialog } from './tmdb-dialog';

const entry: TmdbEntry = {
  animeId: 7, title: 'Sousou no Frieren', status: 'DOUBTFUL', reason: 'CLOSE_CANDIDATE', score: 0.95, locked: false,
  tmdbType: 'tv', tmdbId: 209867, frenchTitle: 'Frieren', hasFrenchSynopsis: true, fetchedAt: null,
  url: 'https://www.themoviedb.org/tv/209867', lastError: null, updatedAt: null, updatedBy: 'auto',
  candidates: [
    { type: 'tv', tmdbId: 209867, name: 'Frieren', originalName: '葬送のフリーレン', year: 2023, animation: true, hasFrenchOverview: true, url: 'u1', score: 1 },
    { type: 'tv', tmdbId: 990001, name: 'Frieren', originalName: '葬送のフリーレン', year: 2023, animation: false, hasFrenchOverview: false, url: 'u2', score: 1 },
  ],
};

describe('parseTmdbRef', () => {
  it('lit une adresse, « type/id » ou un nombre seul (série)', () => {
    expect(parseTmdbRef('https://www.themoviedb.org/tv/209867-frieren?language=fr')).toEqual({ type: 'tv', tmdbId: 209867 });
    expect(parseTmdbRef('movie/372058')).toEqual({ type: 'movie', tmdbId: 372058 });
    expect(parseTmdbRef(' 209867 ')).toEqual({ type: 'tv', tmdbId: 209867 });
    expect(parseTmdbRef('frieren')).toBeNull();
    expect(parseTmdbRef('person/123')).toBeNull();
  });
});

describe('TmdbDialog', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TmdbDialog], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function openWith(e: TmdbEntry) {
    const fixture = TestBed.createComponent(TmdbDialog);
    const messages: string[] = [];
    fixture.componentInstance.changed.subscribe((m) => messages.push(m));
    await fixture.whenStable();
    fixture.componentInstance.open(e);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, messages };
  }

  it('signale un candidat qui n’est pas « Animation »', async () => {
    const { el } = await openWith(entry);
    const items = el.querySelectorAll('.choices li');
    expect(items[0].textContent).not.toContain('pas « Animation »');
    expect(items[1].textContent).toContain('pas « Animation »');
    expect(items[1].textContent).toContain('sans synopsis français');
  });

  it('autre fiche : prévisualise, puis 409 et confirmation avec replace=true', async () => {
    const { fixture, el, messages } = await openWith(entry);
    el.querySelectorAll<HTMLInputElement>('input[type=radio]')[2].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('#td-ref')!;
    input.value = 'https://www.themoviedb.org/tv/555-autre';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.other .btn-small')!.click();
    const prev = http.expectOne((r) => r.url === '/api/admin/anime/7/tmdb/preview');
    expect(prev.request.params.get('type')).toBe('tv');
    expect(prev.request.params.get('tmdbId')).toBe('555');
    prev.flush({ type: 'tv', tmdbId: 555, name: 'Autre', originalName: 'Autre', year: 2020, overview: null, animation: true, url: 'u' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=td-preview]')?.textContent).toContain('Pas de synopsis en français');

    el.querySelector<HTMLButtonElement>('.btn-primary')!.click();
    const first = http.expectOne('/api/admin/anime/7/tmdb');
    expect(first.request.method).toBe('PUT');
    expect(first.request.body).toEqual({ type: 'tv', tmdbId: 555 });
    expect(first.request.params.has('replace')).toBe(false);
    first.flush({
      status: 409, error: 'TMDB_CONFLICT', message: 'déjà une fiche', animeId: 7, animeTitle: 'Sousou no Frieren',
      current: { type: 'tv', tmdbId: 209867, name: 'Frieren', originalName: '葬送のフリーレン', year: 2023, overview: 'x', animation: true, url: 'u' },
      proposed: { type: 'tv', tmdbId: 555, name: 'Autre', originalName: 'Autre', year: 2020, overview: null, animation: true, url: 'u' },
    }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    const box = el.querySelector('.conflict')!;
    expect(box.textContent).toContain('Frieren (葬送のフリーレン) · 2023 · série 209867');
    expect(box.textContent).toContain('Autre · 2020 · série 555');

    el.querySelector<HTMLButtonElement>('.btn-danger')!.click();
    const second = http.expectOne((r) => r.url === '/api/admin/anime/7/tmdb');
    expect(second.request.params.get('replace')).toBe('true');
    second.flush({ ...entry, status: 'MANUAL', locked: true, tmdbId: 555, hasFrenchSynopsis: false });
    await fixture.whenStable();
    expect(messages).toEqual(['« Sousou no Frieren » : fiche TMDB tv/555 appliquée et verrouillée (elle n’a pas de synopsis français).']);
  });

  it('« aucune fiche TMDB » envoie un identifiant nul', async () => {
    const { fixture, el, messages } = await openWith({ ...entry, tmdbType: null, tmdbId: null, candidates: [] });
    el.querySelectorAll<HTMLInputElement>('input[type=radio]')[1].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.btn-primary')!.click();
    const req = http.expectOne('/api/admin/anime/7/tmdb');
    expect(req.request.body).toEqual({ type: null, tmdbId: null });
    req.flush({ ...entry, status: 'MANUAL', locked: true, tmdbType: null, tmdbId: null });
    await fixture.whenStable();
    expect(messages[0]).toContain('aucune fiche TMDB');
  });
});
