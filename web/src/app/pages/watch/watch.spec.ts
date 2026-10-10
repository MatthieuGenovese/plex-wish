import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { WatchPage } from './watch';
import { WebPlayback } from '../../core/playback-api';
import { a11yViolations } from '../../core/a11y-testing';

const base = (extra: Partial<WebPlayback> = {}): WebPlayback => ({
  state: 'READY', mode: 'DIRECT', url: '/api/stream/9?u=1&exp=2&sig=x', mimeType: 'video/mp4', expiresAt: null, growing: false,
  durationSeconds: 1440, audio: [{ id: 0, label: 'Japonais', language: 'jpn', codec: 'aac', isDefault: true }],
  subtitles: [{ id: 0, label: 'Français', language: 'fre', forced: false, isDefault: true, format: 'vtt', url: '/api/stream/9/web/k/sub_0.vtt?s', vttUrl: '/api/stream/9/web/k/sub_0.vtt?s' }],
  fonts: [], imageSubtitles: false, unavailableAudio: [], preparing: null, reason: null,
  episode: { id: 5, animeId: 2, animeTitle: 'Frieren', seasonNumber: 1, episodeNumber: 3, title: 'Tuer des magiciens', durationSeconds: 1440 },
  next: { id: 6, seasonNumber: 1, episodeNumber: 4, title: null }, resume: null, subtitleOffsetSeconds: 0, ...extra,
});

describe('WatchPage (lecteur web)', () => {
  let http: HttpTestingController;
  const played: string[] = [];

  beforeEach(() => {
    played.length = 0;
    localStorage.clear(); // choix de pistes mémorisés par un essai précédent
    // jsdom ne lit pas de vidéo : lecture simulée.
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockImplementation(function (this: HTMLMediaElement) {
      played.push('play');
      this.dispatchEvent(new Event('play'));
      return Promise.resolve();
    });
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(function (this: HTMLMediaElement) {
      played.push('pause');
    });
    vi.spyOn(HTMLMediaElement.prototype, 'load').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      imports: [WatchPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  async function open(id = '5') {
    const fixture = TestBed.createComponent(WatchPage);
    fixture.componentRef.setInput('id', id);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const ask = (episode = 5) => http.expectOne((r) => r.url === `/api/episodes/${episode}/web-playback`);

  it('préparation : état visible avec l’attente estimée, puis lecture toute seule', async () => {
    const { fixture, el } = await open();
    const first = ask();
    expect(first.request.params.get('caps')).not.toBeNull();
    first.flush(base({ state: 'PREPARING', mode: null, url: null,
      preparing: { phase: 'HLS', position: 0, progress: 0.4, estimatedSeconds: 150, retryAfterSeconds: 1, message: 'Préparation de la vidéo pour le navigateur…' } }),
      { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    const card = el.querySelector('[data-testid=preparing]')!;
    expect(card.textContent).toContain('Préparation de la vidéo pour le navigateur');
    expect(card.textContent).toContain('Environ 3 min');
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/anime/2');
    // Redemande toute seule après « retryAfterSeconds ».
    await vi.waitFor(() => ask().flush(base()), { timeout: 3000, interval: 100 });
    await fixture.whenStable();
    expect(el.querySelector('video')!.getAttribute('src')).toBe('/api/stream/9?u=1&exp=2&sig=x');
    expect(played).toContain('play');
    expect(el.querySelector('.player-title')?.textContent).toBe('Frieren');
    expect(el.querySelector('.player-subtitle')?.textContent).toBe('Saison 1 · Épisode 3 · Tuer des magiciens');
    // Sous-titres français choisis par défaut (piste WebVTT native).
    expect(el.querySelector('video track')?.getAttribute('src')).toContain('sub_0.vtt');
    fixture.destroy();
  });

  it('commandes : accessibles, panneau « Audio et sous-titres », clavier, position enregistrée à la pause', async () => {
    const { fixture, el } = await open();
    ask().flush(base({ resume: { positionSeconds: 300, durationSeconds: 1440, completed: false } }));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=notice]')?.textContent).toContain('Reprise à 5:00');
    expect(await a11yViolations(el)).toEqual([]);

    el.querySelector<HTMLButtonElement>('[data-testid=tracks-button]')!.click();
    await fixture.whenStable();
    const panel = el.querySelector('[data-testid=tracks]')!;
    const labels = [...panel.querySelectorAll('label')].map((l) => l.textContent?.trim());
    expect(labels).toEqual(['Désactivés', 'Français']);
    panel.querySelector<HTMLInputElement>('input[type=radio]')!.click(); // Désactivés
    await fixture.whenStable();
    expect(el.querySelector('video track')).toBeNull();
    expect(await a11yViolations(el)).toEqual([]);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=tracks]')).toBeNull();

    // Clavier : K met en pause (lecture en cours), la position part au serveur.
    const video = el.querySelector('video')!;
    Object.defineProperty(video, 'paused', { configurable: true, get: () => false });
    Object.defineProperty(video, 'currentTime', { configurable: true, get: () => 312, set: () => undefined });
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k' }));
    expect(played).toContain('pause');
    video.dispatchEvent(new Event('timeupdate'));
    video.dispatchEvent(new Event('pause'));
    const put = http.expectOne((r) => r.url === '/api/episodes/5/progress' && r.method === 'PUT');
    expect(put.request.body).toEqual({ positionSeconds: 312, durationSeconds: 1440 });
    put.flush({});
    fixture.destroy();
  });

  it('format non pris en charge : raison du serveur, retour à la fiche', async () => {
    const { fixture, el } = await open();
    ask().flush(base({ state: 'UNSUPPORTED', mode: null, url: null, reason: 'La vidéo (MPEG-4 ASP) n’est pas lisible par ce navigateur.' }));
    await fixture.whenStable();
    const card = el.querySelector('[data-testid=unsupported]')!;
    expect(card.getAttribute('role')).toBe('alert');
    expect(card.textContent).toContain('MPEG-4 ASP');
    expect(card.querySelector('a')?.getAttribute('href')).toBe('/anime/2');
    expect(await a11yViolations(el)).toEqual([]);
  });

  it('erreur du serveur : message clair, « Réessayer » redemande', async () => {
    const { fixture, el } = await open();
    ask().flush({ status: 409, error: 'WEB_PREP_FAILED', message: 'Cet épisode n’a pas pu être préparé pour le navigateur (x).' },
      { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    const card = el.querySelector('[data-testid=error]')!;
    expect(card.textContent).toContain('n’a pas pu être préparé');
    card.querySelector<HTMLButtonElement>('button')!.click();
    ask().flush(base());
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=error]')).toBeNull();
    fixture.destroy();
  });

  it('copie HLS sans MediaSource (jsdom) : message « format de diffusion » plutôt qu’un écran noir', async () => {
    const { fixture, el } = await open();
    ask().flush(base({ mode: 'HLS', url: '/api/stream/9/web/k/master.m3u8?a=1&u=1&exp=2&sig=x', mimeType: 'application/vnd.apple.mpegurl' }));
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('[data-testid=error]')?.textContent).toContain('HLS');
    });
  });

  it('sous-titres ASS : JASSUB, et WebVTT (avec un message) s’il ne peut pas démarrer', async () => {
    const { fixture, el } = await open();
    ask().flush(base({ subtitles: [{ id: 0, label: 'Français', language: 'fre', forced: false, isDefault: true, format: 'ass',
      url: '/api/stream/9/web/k/sub_0.ass?s', vttUrl: '/api/stream/9/web/k/sub_0.vtt?s' }], fonts: ['/api/stream/9/web/k/font_0.ttf?s'] }));
    // jsdom n'a ni OffscreenCanvas ni Worker : JASSUB échoue, le lecteur passe au WebVTT.
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(el.querySelector('video track')?.getAttribute('src')).toContain('sub_0.vtt');
    }, { timeout: 4000 });
    expect(el.querySelector('[data-testid=notice]')?.textContent).toContain('sans leurs styles');
    fixture.destroy();
  });

  it('fin de l’épisode : compte à rebours vers l’épisode suivant, annulable', async () => {
    const { fixture, el } = await open();
    ask().flush(base());
    await fixture.whenStable();
    el.querySelector('video')!.dispatchEvent(new Event('ended'));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=next]')?.textContent).toContain('Épisode suivant dans 10 s');
    http.match((r) => r.url === '/api/episodes/5/progress').forEach((r) => r.flush({}));
    el.querySelectorAll<HTMLButtonElement>('[data-testid=next] button')[1].click(); // Annuler
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=next]')).toBeNull();
    fixture.destroy();
  });
});
