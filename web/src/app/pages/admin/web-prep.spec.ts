import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WebPrepSection } from './web-prep';
import { WebAdminOverview } from '../../core/api-types';
import { a11yViolations } from '../../core/a11y-testing';

const label = { episodeId: 5, animeId: 2, animeTitle: 'Frieren', seasonNumber: 1, episodeNumber: 3 };
const overview: WebAdminOverview = {
  settings: { maxHeight: 720, preventive: true, preventiveVideo: false, nightStartHour: 1, nightEndHour: 7 },
  ffmpegVersion: 'ffmpeg version 7.1.5', usable: true, nightOpen: false,
  cache: { hostPath: '/volume2/web-cache', usedBytes: 12_000_000_000, capBytes: 200_000_000_000, freeBytes: 900_000_000_000,
    totalBytes: 2_000_000_000_000, readyBase: 40, readyConverted: 6 },
  running: [{ kind: 'CONV', mediaFileId: 9, episode: label, phase: 'CONVERT', priority: 1, progress: 0.42, speed: 1.4, paused: true }],
  queued: 1,
  queue: [{ kind: 'CONV', mediaFileId: 10, episode: { ...label, episodeNumber: 4 }, status: 'QUEUED', priority: 2, phase: null, error: null,
    attempts: 0, requestedAt: null, nextAttemptAt: null, blocked: false }],
  failures: [{ kind: 'CONV', mediaFileId: 11, episode: { ...label, episodeNumber: 5 }, status: 'FAILED', priority: 0, phase: null,
    error: 'conversion pour le navigateur impossible : code 1', attempts: 3, requestedAt: null, nextAttemptAt: null, blocked: false }],
  speeds: { video720: 1.38, audio: 14 },
};

describe('Administration › Médias › Lecteur web', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [WebPrepSection], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open() {
    const fixture = TestBed.createComponent(WebPrepSection);
    await fixture.whenStable();
    http.expectOne('/api/admin/web').flush(overview);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('cache, en cours (en pause pendant une lecture), file, échecs, vitesses ; accessible', async () => {
    const { fixture, el } = await open();
    expect(el.querySelector('[data-testid=web-cache-status]')?.textContent).toContain('12 Go sur 200 Go');
    expect(el.querySelector('[data-testid=web-cache-status]')?.textContent).toContain('/volume2/web-cache');
    const running = el.querySelector('[data-testid=web-running]')!.textContent!;
    expect(running).toContain('Frieren · S1 · ép. 3');
    expect(running).toContain('conversion de la vidéo (42 %)');
    expect(running).toContain('1,4 × le temps réel');
    expect(running).toContain('en pause : lecture en cours');
    expect(el.querySelector('[data-testid=web-queue]')?.textContent).toContain('préventif (la nuit)');
    expect(el.querySelector('[data-testid=web-failures]')?.textContent).toContain('plus d’essai automatique');
    expect(el.querySelector('[data-testid=web-speeds]')?.textContent).toContain('vidéo 720p 1,4 × le temps réel');
    expect(await a11yViolations(el)).toEqual([]);
    fixture.destroy();
  });

  it('relancer, annuler, réglages enregistrés', async () => {
    const { fixture, el } = await open();
    el.querySelector<HTMLButtonElement>('[data-testid=web-failures] button')!.click();
    http.expectOne((r) => r.url === '/api/admin/web/jobs/11/CONV/retry' && r.method === 'POST').flush(null);
    await fixture.whenStable();
    http.expectOne('/api/admin/web').flush(overview);
    await fixture.whenStable();
    expect(el.textContent).toContain('Frieren · S1 · ép. 5 : remis en file');

    el.querySelector<HTMLButtonElement>('[data-testid=web-running] button')!.click();
    http.expectOne((r) => r.url === '/api/admin/web/jobs/9/CONV' && r.method === 'DELETE').flush(null);
    await fixture.whenStable();
    http.expectOne('/api/admin/web').flush(overview);
    await fixture.whenStable();
    expect(el.textContent).toContain('conversion annulée');

    el.querySelectorAll<HTMLInputElement>('input[name=web-height]')[1].click();
    el.querySelectorAll<HTMLInputElement>('input[type=checkbox]')[1].click();
    el.querySelector<HTMLButtonElement>('form button[type=submit]')!.click();
    const save = http.expectOne('/api/admin/web/settings');
    expect(save.request.body).toEqual({ maxHeight: 1080, preventive: true, preventiveVideo: true });
    save.flush(overview.settings);
    await fixture.whenStable();
    http.expectOne('/api/admin/web').flush(overview);
    await fixture.whenStable();
    expect(el.textContent).toContain('Réglages enregistrés');
    fixture.destroy();
  });
});
