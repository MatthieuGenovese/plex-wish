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

  it('découpe en tranches seulement au-delà de 100 épisodes', () => {
    expect(chunksOf(episodes(100))).toEqual([]);
    expect(chunksOf(episodes(101)).map((c) => c.label)).toEqual(['1–100', '101–101']);
  });
});
