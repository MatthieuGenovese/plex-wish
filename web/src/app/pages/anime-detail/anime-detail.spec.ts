import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AnimeDetailPage, chunksOf } from './anime-detail';
import { EpisodeSummary } from '../../core/api-types';

const episodes = (n: number): EpisodeSummary[] =>
  Array.from({ length: n }, (_, i) => ({ id: i + 1, episodeNumber: i + 1, title: null, durationSeconds: null }));

describe('AnimeDetailPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AnimeDetailPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function open(saison?: number) {
    const fixture = TestBed.createComponent(AnimeDetailPage);
    fixture.componentRef.setInput('id', '7');
    if (saison !== undefined) fixture.componentRef.setInput('saison', saison);
    await fixture.whenStable();
    http.expectOne('/api/anime/7').flush({
      id: 7, title: 'One Piece', alternativeTitle: null, synopsis: null, posterUrl: null, year: null,
      seasons: [
        { id: 70, seasonNumber: 1, label: 'Saison 1', episodeCount: 250 },
        { id: 71, seasonNumber: 0, label: 'Spéciaux', episodeCount: 2 },
      ],
    });
    await fixture.whenStable();
    return fixture;
  }

  const text = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent ?? '';

  it('affiche les saisons (Spéciaux compris) et la première par défaut, par tranches de 100', async () => {
    const fixture = await open();
    http.expectOne('/api/seasons/70/episodes').flush(episodes(250));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect([...el.querySelectorAll('.seasons a')].map((a) => a.textContent?.replace(/\s+/g, ' ').trim()))
      .toEqual(['Saison 1 250', 'Spéciaux 2']);
    expect([...el.querySelectorAll('.chunks button')].map((b) => b.textContent?.trim())).toEqual(['1–100', '101–200', '201–250']);
    expect(el.querySelectorAll('.episode').length).toBe(100);

    (el.querySelectorAll<HTMLButtonElement>('.chunks button')[2]).click();
    await fixture.whenStable();
    expect(el.querySelectorAll('.episode').length).toBe(50);
    expect(el.querySelector('.episode .number')?.textContent).toBe('201');
  });

  it('?saison=0 ouvre les Spéciaux ; un épisode sans titre s’affiche « Épisode N »', async () => {
    const fixture = await open(0);
    http.expectOne('/api/seasons/71/episodes').flush(episodes(2));
    await fixture.whenStable();
    expect(text(fixture)).toContain('Spéciaux · 2 épisodes');
    expect(text(fixture)).toContain('Épisode 2');
    expect((fixture.nativeElement as HTMLElement).querySelector('.chunks')).toBeNull();
  });

  it('format non lisible dans un navigateur : signalé par épisode et pour la saison', async () => {
    const fixture = await open(0);
    const list = episodes(2);
    list[0] = { ...list[0], browserPlayable: false };
    list[1] = { ...list[1], browserPlayable: true };
    http.expectOne('/api/seasons/71/episodes').flush(list);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=browser-note]')?.textContent).toContain('1 épisode est dans un format non lisible dans un navigateur');
    expect(el.querySelector('[data-testid=browser-note]')?.textContent).toContain('application Android');
    expect(el.querySelectorAll('.episode')[0].textContent).toContain('format non lisible dans un navigateur');
    expect(el.querySelectorAll('.episode')[1].textContent).not.toContain('format non lisible');
  });

  it('affiche, année, synopsis et source de la fiche', async () => {
    const fixture = TestBed.createComponent(AnimeDetailPage);
    fixture.componentRef.setInput('id', '8');
    await fixture.whenStable();
    http.expectOne('/api/anime/8').flush({
      id: 8, title: 'Sousou no Frieren', alternativeTitle: 'Frieren: Beyond Journey’s End', year: 2023,
      synopsis: 'Ligne 1.\n\nLigne 2.', synopsisLanguage: 'en', posterUrl: 'https://s4.anilist.co/m.jpg',
      posterLargeUrl: 'https://s4.anilist.co/l.jpg', metadataSource: 'AniList', metadataUrl: 'https://anilist.co/anime/154587',
      seasons: [{ id: 80, seasonNumber: 1, label: 'Saison 1', episodeCount: 1 }],
    });
    await fixture.whenStable();
    http.expectOne('/api/seasons/80/episodes').flush(episodes(1));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.hero img')?.getAttribute('src')).toBe('https://s4.anilist.co/l.jpg');
    expect(el.querySelector('.subtitle')?.textContent).toContain('Frieren: Beyond Journey’s End · 2023');
    expect(el.querySelector('.synopsis')?.getAttribute('lang')).toBe('en');
    expect(el.querySelector('.source')?.textContent?.replace(/\s+/g, ' ')).toContain('Synopsis en anglais (pas de traduction française) · Sources : AniList');
    expect(el.querySelector('.source a')?.getAttribute('href')).toBe('https://anilist.co/anime/154587');
  });

  it('synopsis français de TMDB : titre français, pas de mention « anglais », lien TMDB', async () => {
    const fixture = TestBed.createComponent(AnimeDetailPage);
    fixture.componentRef.setInput('id', '9');
    await fixture.whenStable();
    http.expectOne('/api/anime/9').flush({
      id: 9, title: 'Sousou no Frieren', alternativeTitle: 'Frieren', frenchTitle: 'Frieren', year: 2023,
      synopsis: 'Synopsis en français.', synopsisLanguage: 'fr', synopsisSource: 'TMDB', posterUrl: null, posterLargeUrl: null,
      metadataSource: 'AniList', metadataUrl: 'https://anilist.co/anime/154587', tmdbUrl: 'https://www.themoviedb.org/tv/209867',
      seasons: [{ id: 90, seasonNumber: 1, label: 'Saison 1', episodeCount: 1 }],
    });
    await fixture.whenStable();
    http.expectOne('/api/seasons/90/episodes').flush(episodes(1));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.subtitle')?.textContent?.trim()).toBe('Frieren · 2023');
    expect(el.querySelector('.synopsis')?.getAttribute('lang')).toBe('fr');
    expect(el.querySelector('[data-testid=english-hint]')).toBeNull();
    expect(el.querySelector('.source')?.textContent?.replace(/\s+/g, ' ')).toContain('Sources : AniList · TMDB');
    expect([...el.querySelectorAll('.source a')].map((a) => a.getAttribute('href')))
      .toEqual(['https://anilist.co/anime/154587', 'https://www.themoviedb.org/tv/209867']);
  });

  it('sans fiche : visuel de remplacement, ni synopsis ni source', async () => {
    const fixture = await open();
    http.expectOne('/api/seasons/70/episodes').flush(episodes(1));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.hero [data-testid=poster-placeholder]')?.textContent).toBe('One Piece');
    expect(el.querySelector('.synopsis')).toBeNull();
    expect(el.querySelector('.source')).toBeNull();
  });

  it('découpe en tranches seulement au-delà de 100 épisodes', () => {
    expect(chunksOf(episodes(100))).toEqual([]);
    expect(chunksOf(episodes(101)).map((c) => c.label)).toEqual(['1–100', '101–101']);
  });
});
