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
const entry = {
  mediaFileId: 7, path: 'Air Gear/Saison 1/Air Gear - 1x05.avi', animeTitle: 'Air Gear', seasonNumber: 1, episodeNumber: 5,
  extension: 'avi', fileSize: 192_337_788, status: 'OK', error: null, durationSeconds: 1454.97,
  video: 'MPEG-4 ASP Advanced Simple Profile 640×480', audio: 'MP3 2 ch', subtitles: 'aucune', android: 'REMUX',
  androidReasons: 'conteneur AVI mal lu par Android (horodatage du son)', browserPlayable: false,
  browserReasons: 'conteneur AVI ; MPEG-4 ASP (Xvid/DivX)',
  remuxTest: [
    { variant: 'GENPTS', ok: true, exitCode: 0, timedOut: false, elapsedMs: 1300, message: null },
    { variant: 'GENPTS_UNPACK', ok: false, exitCode: 1, timedOut: false, elapsedMs: 900, message: 'Error applying bitstream filters' },
  ],
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
    http.expectOne((r) => r.url === '/api/admin/media/files').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect(el.textContent).toContain('Test à blanc programmé');
    el.querySelector<HTMLButtonElement>('.remux .btn-danger')!.click();
    http.expectOne('/api/admin/media/remux-test/stop').flush(test);
    await fixture.whenStable();
    http.expectOne('/api/admin/media/summary').flush(summary);
    http.expectOne('/api/admin/media/remux-test').flush(test);
    http.expectOne((r) => r.url === '/api/admin/media/files').flush({ total: 0, page: 0, size: 50, items: [] });
  });

  it('heure de démarrage : aujourd’hui si elle est à venir, sinon demain', () => {
    const now = new Date(2026, 9, 6, 23, 30);
    expect(MediaPage.nextOccurrence('02:00', now).getDate()).toBe(7);
    expect(MediaPage.nextOccurrence('23:45', now).getDate()).toBe(6);
  });
});
