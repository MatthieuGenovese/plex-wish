import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SubtitlesSection, clock } from './subtitles';
import { ExternalSubtitle, SubtitlePage } from '../../core/api-types';
import { a11yViolations } from '../../core/a11y-testing';

const linked: ExternalSubtitle = {
  id: 1, relativePath: 'Captain Tsubasa/sous-titres + police/[sekkusu&ok] Captain Tsubasa - Épisode 27.ass',
  animeFolder: 'Captain Tsubasa', available: true, match: 'NAME', manual: false, forced: false, language: null,
  episode: { id: 70, animeTitle: 'Captain Tsubasa', seasonNumber: 1, episodeNumber: 27 },
  lastCueSeconds: 1800, videoDurationSeconds: 1400, tooLong: true,
};
const unmatched: ExternalSubtitle = {
  ...linked, id: 2, relativePath: 'Days/notes.ass', animeFolder: 'Days', match: null, episode: null,
  lastCueSeconds: 60, videoDurationSeconds: null, tooLong: false, forced: true,
};
const page: SubtitlePage = {
  summary: { total: 2, linked: 1, unmatched: 1, manual: 0, tooLong: 1 }, total: 2, page: 0, size: 50, items: [linked, unmatched],
};

describe('Administration › Corrections › Sous-titres externes', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SubtitlesSection], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open() {
    const fixture = TestBed.createComponent(SubtitlesSection);
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/admin/library/subtitles' && r.params.get('filter') === 'ALL').flush(page);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('liste, association, durée trop longue ; accessible', async () => {
    const { fixture, el } = await open();
    expect(el.querySelector('[data-testid=subs-summary]')?.textContent).toContain('1 sans épisode');
    const rows = [...el.querySelectorAll('tbody tr')].map((r) => r.textContent!);
    expect(rows[0]).toContain('Captain Tsubasa · saison 1 · épisode 27');
    expect(rows[0]).toContain('numéro lu dans le nom');
    expect(rows[0]).toContain('30:00 / 23:20');
    expect(rows[1]).toContain('aucun épisode trouvé');
    expect(rows[1]).toContain('forcés');
    expect(await a11yViolations(el)).toEqual([]);
    fixture.destroy();
  });

  it('rattacher à un épisode, détacher', async () => {
    const { fixture, el } = await open();
    el.querySelectorAll<HTMLButtonElement>('tbody tr')[1].querySelector('button')!.click();
    await fixture.whenStable();
    expect(el.querySelector<HTMLInputElement>('#attach-anime')!.value).toBe('Days');
    const ep = el.querySelector<HTMLInputElement>('#attach-episode')!;
    ep.value = '9';
    ep.dispatchEvent(new Event('input'));
    expect(await a11yViolations(el)).toEqual([]);
    el.querySelector<HTMLFormElement>('form.attach')!.dispatchEvent(new Event('submit'));
    const put = http.expectOne((r) => r.url === '/api/admin/library/subtitles/2/episode' && r.method === 'PUT');
    expect(put.request.body).toEqual({ animeTitle: 'Days', seasonNumber: 1, episodeNumber: 9 });
    put.flush({ ...unmatched, match: 'MANUAL', manual: true, episode: { id: 9, animeTitle: 'Days', seasonNumber: 1, episodeNumber: 9 } });
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/admin/library/subtitles').flush(page);
    await fixture.whenStable();
    expect(el.textContent).toContain('Rattaché à Days · saison 1 · épisode 9.');

    const detach = [...el.querySelectorAll<HTMLButtonElement>('tbody tr')[0].querySelectorAll('button')].find((b) => b.textContent === 'Détacher')!;
    detach.click();
    http.expectOne((r) => r.url === '/api/admin/library/subtitles/1/episode' && r.method === 'DELETE')
      .flush({ ...linked, episode: null, manual: true, match: null });
    await fixture.whenStable();
    http.expectOne((r) => r.url === '/api/admin/library/subtitles').flush(page);
    await fixture.whenStable();
    expect(el.textContent).toContain('détaché, il ne sera plus proposé');
    fixture.destroy();
  });

  it('durées lisibles', () => {
    expect(clock(83)).toBe('1:23');
    expect(clock(3723)).toBe('1:02:03');
    expect(clock(null)).toBe('—');
  });
});
