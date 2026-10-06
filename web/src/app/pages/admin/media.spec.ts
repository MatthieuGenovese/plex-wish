import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MediaPage } from './media';

const summary = {
  enabled: true, ffprobeVersion: 'ffprobe version 7.1.5', workerState: 'working', files: 28254, analyzed: 12000, failed: 3,
  pending: 16251, android: { DIRECT: 11100, REMUX: 880, TRANSCODE: 20 }, browserPlayable: 900, browserNotPlayable: 11100,
  remuxFiles: 880, remuxBytes: 152_000_000_000, episodes: 27000, episodesWithDuration: 12000,
};
const test = {
  running: false, startAt: null, startedAt: null, current: null, total: 880, done: 120, bytesDone: 20_000_000_000,
  bytesTotal: 152_000_000_000, perVariant: { GENPTS: [118, 2], GENPTS_UNPACK: [117, 3] }, estimatedSecondsLeft: null, lastError: null,
};
const cache = {
  usable: true, ffmpegVersion: 'ffmpeg version 7.1.5', cachePath: '/data/remux-cache', maxBytes: 50_000_000_000,
  usedBytes: 2_100_000_000, freeBytes: 900_000_000_000, ready: 11, queued: 2, failed: 1,
  queue: [
    { mediaFileId: 7, path: 'Air Gear/Saison 1/Air Gear - 1x05.avi', status: 'RUNNING', priority: 0, blocked: null, progress: 0.42, requestedAt: '2026-10-06T20:00:00Z' },
    { mediaFileId: 8, path: 'Da Capo/Saison 1/Da Capo - 1x09.ogm', status: 'QUEUED', priority: 1, blocked: 'CACHE_FULL', progress: null, requestedAt: '2026-10-06T20:01:00Z' },
  ],
};
const failed = [{ mediaFileId: 9, path: 'X/X - 01.avi', status: 'FAILED', variant: null, bytes: null, requestedAt: '2026-10-06T19:00:00Z',
  finishedAt: '2026-10-06T19:00:10Z', lastReadAt: null, attempts: 2, nextAttemptAt: '2026-10-06T19:40:00Z',
  error: 'genpts : code 234 : Timestamps are unset' }];
const entry = {
  mediaFileId: 7, path: 'Air Gear/Saison 1/Air Gear - 1x05.avi', animeId: 3, animeTitle: 'Air Gear', seasonNumber: 1, episodeNumber: 5,
  extension: 'avi', fileSize: 192_337_788, status: 'OK', error: null, durationSeconds: 1454.97,
  video: 'MPEG-4 ASP Advanced Simple Profile 640×480', audio: 'MP3 2 ch', subtitles: 'aucune', android: 'REMUX',
  androidReasons: 'conteneur AVI mal lu par Android (horodatage du son)', browserPlayable: false,
  browserReasons: 'conteneur AVI ; MPEG-4 ASP (Xvid/DivX)',
  remuxTest: [
    { variant: 'GENPTS', ok: true, exitCode: 0, timedOut: false, elapsedMs: 1300, message: null },
    { variant: 'GENPTS_UNPACK', ok: false, exitCode: 1, timedOut: false, elapsedMs: 900, message: 'Error applying bitstream filters' },
  ],
  remuxStatus: 'READY', remuxError: null,
};

describe('MediaPage (admin)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MediaPage], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(filter?: string) {
    const fixture = TestBed.createComponent(MediaPage);
    if (filter) fixture.componentRef.setInput('filtre', filter);
    await fixture.whenStable();
    http.expectOne('/api/admin/media/summary').flush(summary);
    http.expectOne('/api/admin/media/remux-test').flush(test);
    http.expectOne('/api/admin/media/remux').flush(cache);
    http.expectOne((r) => r.url === '/api/admin/media/remux/jobs').flush(failed);
    const list = http.expectOne((r) => r.url === '/api/admin/media/files');
    return { fixture, list, el: fixture.nativeElement as HTMLElement };
  }

  it('avancement, catégories, espace de remux, liste filtrée avec pistes et résultat du test à blanc', async () => {
    const { fixture, list, el } = await open('REMUX');
    expect(list.request.params.get('filter')).toBe('REMUX');
    list.flush({ total: 1, page: 0, size: 50, items: [entry] });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=media-status]')?.textContent).toContain('16\u202f251 en attente');
    expect(el.querySelector('[data-testid=media-status]')?.textContent).toContain('ffprobe version 7.1.5');
    expect(el.textContent).toContain('Remux nécessaire');
    expect(el.querySelector('[data-testid=media-remux-space]')?.textContent).toContain('152 Go');
    expect(el.querySelector('[data-testid=remux-test-status]')?.textContent).toContain('120 / 880 fichiers testés');
    const row = el.querySelector('tbody tr')!;
    expect(row.textContent).toContain('24 min 15');
    expect(row.textContent).toContain('MP3 2 ch');
    expect(row.textContent).toContain('code 1 : Error applying bitstream filters');
    expect(row.textContent).toContain('conteneur AVI ; MPEG-4 ASP');
  });

  it('test à blanc : lancement programmé à l’heure choisie, arrêt', async () => {
    const { fixture, list, el } = await open();
    list.flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    const buttons = [...el.querySelectorAll<HTMLButtonElement>('.remux button')];
    buttons.find((b) => b.textContent?.includes('Programmer'))!.click();
    const start = http.expectOne((r) => r.url === '/api/admin/media/remux-test/start');
    const at = new Date(start.request.params.get('startAt')!);
    expect(at.getHours()).toBe(2);
    expect(at.getTime()).toBeGreaterThan(Date.now());
    start.flush({ ...test, running: true, startAt: at.toISOString() });
    await fixture.whenStable();
    http.expectOne('/api/admin/media/summary').flush(summary);
    http.expectOne('/api/admin/media/remux-test').flush({ ...test, running: true, startAt: at.toISOString() });
    http.expectOne('/api/admin/media/remux').flush(cache);
    http.expectOne((r) => r.url === '/api/admin/media/remux/jobs').flush([]);
    http.expectOne((r) => r.url === '/api/admin/media/files').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect(el.textContent).toContain('Test à blanc programmé');
    el.querySelector<HTMLButtonElement>('.remux .btn-danger')!.click();
    http.expectOne('/api/admin/media/remux-test/stop').flush(test);
    await fixture.whenStable();
    http.expectOne('/api/admin/media/summary').flush(summary);
    http.expectOne('/api/admin/media/remux-test').flush(test);
    http.expectOne('/api/admin/media/remux').flush(cache);
    http.expectOne((r) => r.url === '/api/admin/media/remux/jobs').flush([]);
    http.expectOne((r) => r.url === '/api/admin/media/files').flush({ total: 0, page: 0, size: 50, items: [] });
  });

  it('remux à la demande : cache, file, échecs avec « Relancer », préparer un animé, vider le cache', async () => {
    const { fixture, list, el } = await open('REMUX');
    list.flush({ total: 1, page: 0, size: 50, items: [entry] });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=remux-cache]')?.textContent).toContain('2,1 Go sur 50 Go');
    expect(el.textContent).toContain('en cours (42 %)');
    expect(el.textContent).toContain('cache plein : copies en cours de lecture');
    expect(el.textContent).toContain('Timestamps are unset');
    expect(el.querySelector('tbody tr')?.textContent).toContain('Copie prête');

    el.querySelector<HTMLButtonElement>('.failures button')!.click();
    http.expectOne('/api/admin/media/remux/jobs/9/retry').flush({ queued: true });
    await fixture.whenStable();
    reload();
    await fixture.whenStable();
    expect(el.textContent).toContain('Remux remis en file');

    [...el.querySelectorAll<HTMLButtonElement>('tbody button')].find((b) => b.textContent?.includes('Préparer'))!.click();
    http.expectOne('/api/admin/media/remux/anime/3/prepare').flush({ queued: 12, bytes: 2_000_000_000 });
    await fixture.whenStable();
    reload();
    await fixture.whenStable();

    [...el.querySelectorAll<HTMLButtonElement>('.remux button')].find((b) => b.textContent?.includes('Vider le cache'))!.click();
    await fixture.whenStable();
    [...el.querySelectorAll<HTMLButtonElement>('.remux .btn-danger')].find((b) => b.textContent?.includes('Vider le cache'))!.click();
    const clear = http.expectOne((r) => r.url === '/api/admin/media/remux/clear');
    expect(clear.request.params.get('confirm')).toBe('true');
    clear.flush({ removed: 9, keptInUse: 2 });
    await fixture.whenStable();
    reload();

    function reload() {
      http.expectOne('/api/admin/media/summary').flush(summary);
      http.expectOne('/api/admin/media/remux-test').flush(test);
      http.expectOne('/api/admin/media/remux').flush(cache);
      http.expectOne((r) => r.url === '/api/admin/media/remux/jobs').flush(failed);
      http.expectOne((r) => r.url === '/api/admin/media/files').flush({ total: 1, page: 0, size: 50, items: [entry] });
    }
  });

  it('heure de démarrage : aujourd’hui si elle est à venir, sinon demain', () => {
    const now = new Date(2026, 9, 6, 23, 30);
    expect(MediaPage.nextOccurrence('02:00', now).getDate()).toBe(7);
    expect(MediaPage.nextOccurrence('23:45', now).getDate()).toBe(6);
  });
});
