import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { PlaybackApi, WebPlayback } from '../../core/playback-api';
import { detectCaps } from '../../core/media-caps';
import { errorMessage } from '../../core/errors';
import { Icon } from '../../shared/icon';
import { SubtitleRenderer, VttSubtitles } from './subtitles';
import { AssSubtitles } from './ass-subtitles';
import {
  episodeLine,
  formatTime,
  loadPref,
  pickAudio,
  pickSubtitle,
  reportable,
  resumeFrom,
  savePref,
  spokenTime,
} from './player-logic';

type Phase = 'loading' | 'preparing' | 'ready' | 'unsupported' | 'error';

/** Partie de hls.js utilisée ici (chargée seulement pour une copie HLS). */
interface HlsLike {
  loadSource(url: string): void;
  attachMedia(video: HTMLVideoElement): void;
  destroy(): void;
  startLoad(position?: number): void;
  recoverMediaError(): void;
  audioTrack: number;
  on(event: string, cb: (event: string, data: { fatal?: boolean; type?: string; details?: string }) => void): void;
}

const NEXT_DELAY = 10;
const HIDE_AFTER_MS = 3000;
const REPORT_EVERY_MS = 10_000;

/**
 * Lecteur web (phase 10, docs/WEB-PLAYER.md §7) : plein cadre, commandes en surcouche (masquées pendant la lecture),
 * clavier, plein écran, gestes sur téléphone, choix audio et sous-titres (mémorisés sur l'appareil), reprise et
 * progression (comme Android), épisode suivant. Le serveur choisit la source (original ou copie HLS) d'après ce que
 * le navigateur décode ; pendant une préparation, état visible et lecture dès que possible.
 */
@Component({
  selector: 'app-watch',
  imports: [Icon, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown)': 'onKey($event)',
    '(document:visibilitychange)': 'onVisibility()',
    '(document:fullscreenchange)': 'onFullscreenChange()',
    '(window:pagehide)': 'saveOnExit()',
  },
  template: `
    <div class="player" #root [class.controls-hidden]="!showControls()" (pointermove)="onPointer()" (pointerdown)="onPointer()"
         role="region" [attr.aria-label]="'Lecteur : ' + titleText()">
      <video #video class="player-video" playsinline preload="auto" [attr.aria-label]="titleText()"
             (play)="onPlay()" (pause)="onPause()" (timeupdate)="onTime()" (durationchange)="onTime()" (progress)="onTime()"
             (waiting)="buffering.set(true)" (playing)="buffering.set(false)" (canplay)="buffering.set(false)"
             (ended)="onEnded()" (error)="onVideoError()" (volumechange)="onVolume()"></video>

      @if (phase() === 'ready') {
        <div class="player-tap" (click)="onTap($event)" aria-hidden="true"></div>
      }

      @if (phase() === 'loading' || (phase() === 'ready' && buffering())) {
        <div class="player-busy" role="status"><span class="spinner" aria-hidden="true"></span>
          <span class="visually-hidden">Chargement…</span></div>
      }

      @switch (phase()) {
        @case ('preparing') {
          <div class="player-card" role="status" aria-live="polite" data-testid="preparing">
            <span class="spinner" aria-hidden="true"></span>
            <h1 class="player-card-title">{{ info()?.preparing?.message ?? 'Préparation pour le navigateur…' }}</h1>
            @if (info()?.preparing?.progress != null) {
              <div class="player-bar" aria-hidden="true"><i [style.width.%]="(info()!.preparing!.progress ?? 0) * 100"></i></div>
            }
            <p class="player-card-text">{{ prepText() }}</p>
            <p class="player-card-text">La lecture commencera toute seule. Vous pouvez aussi revenir plus tard : la préparation continue.</p>
            <a class="btn" [routerLink]="backLink()">Retour à la fiche</a>
          </div>
        }
        @case ('unsupported') {
          <div class="player-card" role="alert" data-testid="unsupported">
            <app-icon name="info" size="lg" />
            <h1 class="player-card-title">Format non pris en charge par ce navigateur</h1>
            <p class="player-card-text">{{ info()?.reason }}</p>
            <a class="btn btn-primary" [routerLink]="backLink()">Retour à la fiche</a>
          </div>
        }
        @case ('error') {
          <div class="player-card" role="alert" data-testid="error">
            <app-icon name="error" size="lg" />
            <h1 class="player-card-title">Lecture impossible</h1>
            <p class="player-card-text">{{ errorText() }}</p>
            <div class="player-card-actions">
              <button type="button" class="btn btn-primary" (click)="retry()">Réessayer</button>
              <a class="btn" [routerLink]="backLink()">Retour à la fiche</a>
              @if (errorDetails()) {
                <button type="button" class="btn" [attr.aria-expanded]="showDetails()" (click)="showDetails.set(!showDetails())">Détails</button>
              }
            </div>
            @if (showDetails() && errorDetails()) {
              <p class="player-details">{{ errorDetails() }}</p>
            }
          </div>
        }
      }

      @if (phase() === 'ready') {
        <div class="player-controls" (focusin)="poke()">
          <div class="player-top">
            <a class="player-round" [routerLink]="backLink()" aria-label="Retour à la fiche"><app-icon name="arrow_back" /></a>
            <div class="player-titles">
              <h1 class="player-title">{{ info()?.episode?.animeTitle }}</h1>
              <p class="player-subtitle">{{ line() }}</p>
              @if (info()?.growing) {
                <p class="player-note" data-testid="growing">Préparation en cours : la suite arrive pendant la lecture.</p>
              }
            </div>
          </div>

          <div class="player-center">
            <button type="button" class="player-round" (click)="seekBy(-10)" aria-label="Reculer de 10 secondes"><app-icon name="replay_10" /></button>
            <button type="button" class="player-round player-main" (click)="toggle()" [attr.aria-label]="playing() ? 'Pause' : 'Lecture'"
                    data-testid="play"><app-icon [name]="playing() ? 'pause_fill' : 'play_arrow_fill'" size="lg" /></button>
            <button type="button" class="player-round" (click)="seekBy(10)" aria-label="Avancer de 10 secondes"><app-icon name="forward_10" /></button>
          </div>

          <div class="player-bottom">
            <div class="player-progress">
              <div class="player-buffered" aria-hidden="true" [style.width.%]="bufferedPct()"></div>
              <input type="range" class="player-seek" min="0" [max]="duration() || 0" step="1" [value]="position()"
                     aria-label="Position dans l'épisode" [attr.aria-valuetext]="valueText()" data-testid="seek"
                     (input)="onSeekInput($event)" />
            </div>
            <div class="player-row">
              <span class="player-time num" data-testid="time">{{ timeText() }}</span>
              <span class="player-spacer"></span>
              <button type="button" class="player-round player-small" (click)="toggleMute()"
                      [attr.aria-label]="muted() ? 'Rétablir le son' : 'Couper le son'"><app-icon [name]="muted() ? 'volume_off' : 'volume_up'" /></button>
              <input type="range" class="player-volume" min="0" max="1" step="0.05" [value]="muted() ? 0 : volume()"
                     aria-label="Volume" [attr.aria-valuetext]="volumeText()" (input)="onVolumeInput($event)" />
              <button type="button" class="player-pill" (click)="togglePanel()" [attr.aria-expanded]="panelOpen()" aria-controls="player-tracks"
                      data-testid="tracks-button"><app-icon name="subtitles" /><span>Audio et sous-titres</span></button>
              @if (info()?.next) {
                <button type="button" class="player-round player-small" (click)="goNext()" aria-label="Épisode suivant"><app-icon name="skip_next" /></button>
              }
              <button type="button" class="player-round player-small" (click)="toggleFullscreen()"
                      [attr.aria-label]="fullscreen() ? 'Quitter le plein écran' : 'Plein écran'"><app-icon [name]="fullscreen() ? 'fullscreen_exit' : 'fullscreen'" /></button>
            </div>
          </div>
        </div>
      }

      @if (panelOpen() && phase() === 'ready') {
        <div class="player-panel" id="player-tracks" role="dialog" aria-labelledby="player-tracks-title" data-testid="tracks">
          <div class="player-panel-head">
            <h2 id="player-tracks-title">Audio et sous-titres</h2>
            <button type="button" class="player-round player-small" (click)="panelOpen.set(false)" aria-label="Fermer"><app-icon name="close" /></button>
          </div>
          <p class="player-panel-hint">Votre choix est gardé sur cet appareil pour les épisodes suivants.</p>
          @if ((info()?.audio?.length ?? 0) > 1 || (info()?.unavailableAudio?.length ?? 0) > 0) {
            <fieldset>
              <legend>Audio</legend>
              @for (a of info()!.audio; track a.id) {
                <label class="player-option"><input type="radio" name="audio" [checked]="audioIndex() === a.id" (change)="selectAudio(a.id)" />
                  {{ a.label }}</label>
              }
              @if (info()!.unavailableAudio.length) {
                <p class="player-panel-hint">Non disponible dans le navigateur (à convertir) : {{ info()!.unavailableAudio.join(', ') }}.</p>
              }
            </fieldset>
          }
          <fieldset>
            <legend>Sous-titres</legend>
            <label class="player-option"><input type="radio" name="subs" [checked]="subtitleId() === null" (change)="selectSubtitle(null)" /> Désactivés</label>
            @for (s of info()!.subtitles; track s.id) {
              <label class="player-option"><input type="radio" name="subs" [checked]="subtitleId() === s.id" (change)="selectSubtitle(s.id)" />
                {{ s.label }}</label>
            }
          </fieldset>
        </div>
      }

      @if (notice(); as n) {
        <div class="player-toast" role="status" data-testid="notice">
          <span>{{ n.text }}</span>
          @if (n.action) {
            <button type="button" class="btn btn-small" (click)="n.action.run()">{{ n.action.label }}</button>
          }
          <button type="button" class="player-round player-small" (click)="notice.set(null)" aria-label="Fermer le message"><app-icon name="close" /></button>
        </div>
      }

      @if (nextIn() !== null && info()?.next; as next) {
        <div class="player-next" role="dialog" aria-labelledby="player-next-title" data-testid="next">
          <h2 id="player-next-title">Épisode suivant dans {{ nextIn() }} s</h2>
          <p>{{ nextLine() }}</p>
          <div class="player-card-actions">
            <button type="button" class="btn btn-primary" (click)="goNext()">Lire maintenant</button>
            <button type="button" class="btn" (click)="cancelNext()">Annuler</button>
          </div>
        </div>
      }

      <p class="visually-hidden" aria-live="polite">{{ announce() }}</p>
    </div>
  `,
})
export class WatchPage {
  /** Identifiant de l'épisode (route /regarder/:id). */
  readonly id = input.required<string>();

  private readonly api = inject(PlaybackApi);
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  private readonly videoRef = viewChild.required<ElementRef<HTMLVideoElement>>('video');
  private readonly rootRef = viewChild.required<ElementRef<HTMLElement>>('root');

  protected readonly phase = signal<Phase>('loading');
  protected readonly info = signal<WebPlayback | null>(null);
  protected readonly playing = signal(false);
  protected readonly buffering = signal(false);
  protected readonly position = signal(0);
  protected readonly duration = signal(0);
  protected readonly buffered = signal(0);
  protected readonly volume = signal(1);
  protected readonly muted = signal(false);
  protected readonly fullscreen = signal(false);
  protected readonly controlsVisible = signal(true);
  protected readonly panelOpen = signal(false);
  protected readonly audioIndex = signal(0);
  protected readonly subtitleId = signal<number | null>(null);
  protected readonly errorText = signal('');
  protected readonly errorDetails = signal<string | null>(null);
  protected readonly showDetails = signal(false);
  protected readonly announce = signal('');
  protected readonly notice = signal<{ text: string; action?: { label: string; run: () => void } } | null>(null);
  protected readonly nextIn = signal<number | null>(null);

  protected readonly showControls = computed(
    () => this.phase() !== 'ready' || this.controlsVisible() || !this.playing() || this.panelOpen(),
  );
  protected readonly titleText = computed(() => {
    const i = this.info();
    return i ? `${i.episode.animeTitle}, ${episodeLine(i.episode)}` : 'Lecture';
  });
  protected readonly line = computed(() => (this.info() ? episodeLine(this.info()!.episode) : ''));
  protected readonly backLink = computed(() => {
    const i = this.info();
    return i ? ['/anime', String(i.episode.animeId)] : ['/'];
  });
  protected readonly timeText = computed(() => `${formatTime(this.position())} / ${formatTime(this.duration())}`);
  protected readonly valueText = computed(() => `${spokenTime(this.position())} sur ${spokenTime(this.duration())}`);
  protected readonly volumeText = computed(() => (this.muted() ? 'son coupé' : Math.round(this.volume() * 100) + ' %'));
  protected readonly bufferedPct = computed(() => (this.duration() > 0 ? Math.min(100, (this.buffered() / this.duration()) * 100) : 0));
  protected readonly nextLine = computed(() => {
    const n = this.info()?.next;
    if (!n) return '';
    return [n.seasonNumber === 0 ? 'Spéciaux' : 'Saison ' + n.seasonNumber, 'Épisode ' + n.episodeNumber, n.title].filter(Boolean).join(' · ');
  });
  protected readonly prepText = computed(() => {
    const p = this.info()?.preparing;
    if (!p) return '';
    const minutes = Math.max(1, Math.round(p.estimatedSeconds / 60));
    return p.estimatedSeconds < 60 ? 'Prêt dans moins d’une minute.' : `Environ ${minutes} min.`;
  });

  private hls: HlsLike | null = null;
  private subs: SubtitleRenderer | null = null;
  private episodeId = 0;
  private generation = 0;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;
  private hideTimer: ReturnType<typeof setTimeout> | undefined;
  private reportTimer: ReturnType<typeof setInterval> | undefined;
  private nextTimer: ReturnType<typeof setInterval> | undefined;
  private tapTimer: ReturnType<typeof setTimeout> | undefined;
  private lastReported = -1;
  private reloads = 0;
  private resumeAt = 0;
  private readonly caps = detectCaps();

  constructor() {
    effect(() => {
      const id = Number(this.id());
      untracked(() => this.open(id));
    });
    // Sous-titres WebVTT au-dessus de la barre de commandes quand elle est visible.
    effect(() => {
      const raised = this.showControls() && this.phase() === 'ready';
      untracked(() => this.subs?.raise?.(raised));
    });
    inject(DestroyRef).onDestroy(() => this.teardown(true));
    const v = Number(loadPref('volume'));
    if (Number.isFinite(v) && v >= 0 && v <= 1 && loadPref('volume') !== null) {
      this.volume.set(v);
    }
  }

  // --- Chargement ---------------------------------------------------------------------------------------------------

  private open(id: number): void {
    this.teardown(true);
    this.episodeId = id;
    this.generation++;
    this.reloads = 0;
    this.phase.set('loading');
    this.info.set(null);
    this.panelOpen.set(false);
    this.nextIn.set(null);
    this.notice.set(null);
    this.ask(this.generation);
  }

  /** Demande au serveur quoi lire ; redemande pendant une préparation. */
  private ask(gen: number, keepPosition?: number): void {
    this.api.webPlayback(this.episodeId, this.caps).subscribe({
      next: (res) => {
        if (gen !== this.generation) return;
        const body = res.body!;
        this.info.set(body);
        this.title.setTitle(`${body.episode.animeTitle} · Épisode ${body.episode.episodeNumber}`);
        if (body.state === 'UNSUPPORTED') {
          this.phase.set('unsupported');
          this.say('Format non pris en charge par ce navigateur.');
        } else if (body.state === 'PREPARING') {
          if (this.phase() !== 'preparing') this.say(body.preparing?.message ?? 'Préparation pour le navigateur.');
          this.phase.set('preparing');
          const wait = Math.max(1, body.preparing?.retryAfterSeconds ?? 3) * 1000;
          this.pollTimer = setTimeout(() => this.ask(gen, keepPosition), wait);
        } else {
          this.start(body, keepPosition);
        }
      },
      error: (err: unknown) => {
        if (gen !== this.generation) return;
        this.fail(errorMessage(err, 'Cet épisode ne peut pas être lu pour le moment.'),
          err instanceof HttpErrorResponse ? `HTTP ${err.status}` : null);
      },
    });
  }

  private async start(info: WebPlayback, keepPosition?: number): Promise<void> {
    const gen = this.generation;
    const video = this.videoRef().nativeElement;
    this.detachMedia();
    this.phase.set('ready');
    this.controlsVisible.set(true);
    this.resumeAt = keepPosition ?? resumeFrom(info);
    video.volume = this.volume();
    video.muted = this.muted();
    if (info.mode === 'HLS') {
      const { default: Hls } = await import('hls.js');
      if (gen !== this.generation) return;
      if (Hls.isSupported()) {
        // Pas de worker (pistes fMP4 : rien à convertir) : aucun « blob: » dans worker-src (docs/WEB-PLAYER.md §9, S3).
        const hls = new Hls({ enableWorker: false, startPosition: this.resumeAt, maxBufferLength: 30, backBufferLength: 60 });
        this.hls = hls as unknown as HlsLike;
        hls.on(Hls.Events.ERROR, (_e, data) => this.onHlsError(data.fatal ?? false, String(data.type), String(data.details)));
        hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => this.applyAudio());
        hls.loadSource(info.url!);
        hls.attachMedia(video);
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = info.url!;
        video.currentTime = this.resumeAt;
      } else {
        this.fail('Ce navigateur ne sait pas lire ce format de diffusion (HLS).', 'MediaSource absent');
        return;
      }
    } else {
      video.src = info.url!;
      if (this.resumeAt > 0) {
        video.addEventListener('loadedmetadata', () => (video.currentTime = this.resumeAt), { once: true });
      }
    }
    this.audioIndex.set(pickAudio(info.audio, loadPref('audio')));
    this.applySubtitle(pickSubtitle(info.subtitles, loadPref('subtitles')));
    void video.play().catch(() => {
      // Lecture automatique refusée (son) : on attend un geste de l'utilisateur.
      this.playing.set(false);
    });
    if (keepPosition === undefined && this.resumeAt > 0) {
      this.notice.set({ text: `Reprise à ${formatTime(this.resumeAt)}`, action: { label: 'Recommencer', run: () => this.restart() } });
      setTimeout(() => this.notice.update((n) => (n?.text.startsWith('Reprise') ? null : n)), 8000);
    }
    if (info.imageSubtitles) {
      this.notice.set({ text: 'Sous-titres en image : non affichables dans le navigateur. Utilisez l’application Android.' });
    }
    this.reportTimer = setInterval(() => {
      if (!video.paused) this.report();
    }, REPORT_EVERY_MS);
    this.scheduleHide();
  }

  // --- Pistes -------------------------------------------------------------------------------------------------------

  private applyAudio(): void {
    if (this.hls && this.info()?.mode === 'HLS') {
      this.hls.audioTrack = this.audioIndex();
    }
  }

  protected selectAudio(index: number): void {
    this.audioIndex.set(index);
    this.applyAudio();
    const lang = this.info()?.audio[index]?.language;
    if (lang) savePref('audio', lang);
  }

  protected selectSubtitle(id: number | null): void {
    this.applySubtitle(id);
    const t = id === null ? null : this.info()?.subtitles.find((s) => s.id === id);
    savePref('subtitles', t ? (t.language ?? 'und') : 'off');
  }

  private applySubtitle(id: number | null): void {
    this.subs?.destroy();
    this.subs = null;
    this.subtitleId.set(id);
    const info = this.info();
    const t = id === null ? null : info?.subtitles.find((s) => s.id === id);
    if (!t || !info) return;
    const video = this.videoRef().nativeElement;
    const offset = info.mode === 'HLS' ? info.subtitleOffsetSeconds : 0;
    if (t.format === 'ass' && !this.assBroken) {
      // ASS : rendu fidèle par JASSUB ; s'il ne démarre pas (navigateur trop ancien, WebAssembly bloqué), WebVTT.
      this.subs = new AssSubtitles(video, t.url, info.fonts, offset, () => {
        this.assBroken = true;
        if (this.subtitleId() === id) {
          this.applySubtitle(id);
          this.notice.set({ text: 'Sous-titres affichés sans leurs styles (rendu avancé indisponible dans ce navigateur).' });
        }
      });
      return;
    }
    const url = t.format === 'vtt' ? t.url : t.vttUrl;
    if (url) {
      this.subs = new VttSubtitles(video, t, url, offset);
      this.subs.raise?.(this.showControls());
    }
  }

  /** JASSUB n'a pas pu démarrer : WebVTT pour la suite de la séance. */
  private assBroken = false;

  // --- Commandes ----------------------------------------------------------------------------------------------------

  protected toggle(): void {
    const v = this.videoRef().nativeElement;
    if (v.paused) {
      void v.play().catch(() => undefined);
    } else {
      v.pause();
    }
  }

  protected seekBy(delta: number): void {
    const v = this.videoRef().nativeElement;
    this.seekTo(v.currentTime + delta);
    this.say(`${delta > 0 ? 'Avance' : 'Recul'} de ${Math.abs(delta)} secondes`);
  }

  private seekTo(t: number): void {
    const v = this.videoRef().nativeElement;
    const max = Number.isFinite(v.duration) ? v.duration : this.duration();
    v.currentTime = Math.max(0, Math.min(max || 0, t));
    this.position.set(v.currentTime);
    this.poke();
  }

  protected onSeekInput(e: Event): void {
    this.seekTo(Number((e.target as HTMLInputElement).value));
  }

  protected restart(): void {
    this.seekTo(0);
    this.notice.set(null);
  }

  protected toggleMute(): void {
    const v = this.videoRef().nativeElement;
    v.muted = !v.muted;
    if (!v.muted && v.volume === 0) v.volume = 0.5;
  }

  protected onVolumeInput(e: Event): void {
    const v = this.videoRef().nativeElement;
    v.volume = Number((e.target as HTMLInputElement).value);
    v.muted = v.volume === 0;
  }

  protected onVolume(): void {
    const v = this.videoRef().nativeElement;
    this.volume.set(v.volume);
    this.muted.set(v.muted);
    savePref('volume', String(v.volume));
  }

  protected togglePanel(): void {
    this.panelOpen.update((o) => !o);
    if (this.panelOpen()) {
      queueMicrotask(() => this.rootRef().nativeElement.querySelector<HTMLInputElement>('.player-panel input:checked')?.focus());
    }
  }

  protected async toggleFullscreen(): Promise<void> {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else {
        await this.rootRef().nativeElement.requestFullscreen();
        const o = screen.orientation as ScreenOrientation & { lock?: (o: string) => Promise<void> };
        await o?.lock?.('landscape').catch(() => undefined);
      }
    } catch {
      // plein écran refusé (iframe, navigateur) : on reste dans la page
    }
  }

  protected goNext(): void {
    const next = this.info()?.next;
    this.cancelNext();
    if (next) {
      void this.router.navigate(['/regarder', next.id], { replaceUrl: true });
    }
  }

  protected cancelNext(): void {
    clearInterval(this.nextTimer);
    this.nextIn.set(null);
  }

  // --- Événements de la vidéo ---------------------------------------------------------------------------------------

  protected onPlay(): void {
    this.playing.set(true);
    this.say('Lecture');
    this.scheduleHide();
  }

  protected onPause(): void {
    this.playing.set(false);
    this.controlsVisible.set(true);
    if (!this.videoRef().nativeElement.ended) {
      this.say('Pause');
      this.report();
    }
  }

  protected onTime(): void {
    const v = this.videoRef().nativeElement;
    this.position.set(v.currentTime);
    const d = Number.isFinite(v.duration) && v.duration > 0 ? v.duration : (this.info()?.durationSeconds ?? 0);
    this.duration.set(d);
    if (v.buffered.length) {
      this.buffered.set(v.buffered.end(v.buffered.length - 1));
    }
  }

  protected onEnded(): void {
    this.playing.set(false);
    this.controlsVisible.set(true);
    const d = this.duration();
    if (d > 0) {
      this.lastReported = -1;
      this.send(d, d);
    }
    if (this.info()?.next) {
      this.nextIn.set(NEXT_DELAY);
      this.nextTimer = setInterval(() => {
        const n = (this.nextIn() ?? 1) - 1;
        if (n <= 0) {
          this.goNext();
        } else {
          this.nextIn.set(n);
        }
      }, 1000);
    } else {
      this.say('Fin de l’épisode');
    }
  }

  protected onVideoError(): void {
    if (this.hls || this.phase() !== 'ready') return; // hls.js signale ses erreurs lui-même
    const v = this.videoRef().nativeElement;
    const code = v.error?.code;
    // Lien expiré (403) ou coupure : nouveau lien et reprise au même endroit, une fois.
    if (code === MediaError.MEDIA_ERR_NETWORK || code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
      if (this.reload()) return;
    }
    this.fail(code === MediaError.MEDIA_ERR_DECODE ? 'Le navigateur n’a pas pu décoder cette vidéo.' : 'La lecture s’est interrompue.',
      `MediaError ${code ?? '?'}`);
  }

  private recovered = false;

  private onHlsError(fatal: boolean, type: string, details: string): void {
    if (!fatal) return;
    if (type === 'mediaError' && !this.recovered) {
      this.recovered = true;
      this.hls?.recoverMediaError();
      return;
    }
    if (type === 'networkError' && this.reload()) return;
    this.fail(type === 'networkError' ? 'Connexion au serveur perdue. Vérifiez le réseau, puis réessayez.'
      : 'Le navigateur n’a pas pu lire cet épisode.', `hls.js : ${type} / ${details}`);
  }

  /** Redemande la source (nouvelles signatures) et reprend à la même position ; au plus 3 fois par épisode. */
  private reload(): boolean {
    if (this.reloads >= 3) return false;
    this.reloads++;
    const at = this.videoRef().nativeElement.currentTime || this.position();
    this.ask(this.generation, at);
    return true;
  }

  private fail(message: string, details: string | null): void {
    this.detachMedia();
    this.errorText.set(message);
    this.errorDetails.set(details);
    this.showDetails.set(false);
    this.phase.set('error');
    this.say('Lecture impossible : ' + message);
  }

  protected retry(): void {
    const at = this.position();
    this.phase.set('loading');
    this.reloads = 0;
    this.recovered = false;
    this.ask(this.generation, at > 0 ? at : undefined);
  }

  // --- Clavier, gestes, surcouche -----------------------------------------------------------------------------------

  /** Dernière interaction au clavier (Tab dans les commandes) plutôt qu'à la souris ou au doigt. */
  private keyboard = false;

  protected onKey(e: KeyboardEvent): void {
    this.keyboard = true;
    if (this.phase() !== 'ready' || e.ctrlKey || e.metaKey || e.altKey) return;
    const target = e.target as HTMLElement | null;
    const tag = target?.tagName;
    const typing = tag === 'INPUT' && (target as HTMLInputElement).type !== 'range' && (target as HTMLInputElement).type !== 'radio';
    if (typing || tag === 'TEXTAREA' || tag === 'SELECT') return;
    const onControl = tag === 'BUTTON' || tag === 'A' || (tag === 'INPUT' && (target as HTMLInputElement).type === 'range');
    const key = e.key;
    let handled = true;
    if ((key === ' ' && !onControl) || key === 'k' || key === 'K') {
      this.toggle();
    } else if ((key === 'ArrowLeft' && !onControl) || key === 'j' || key === 'J') {
      this.seekBy(-10);
    } else if ((key === 'ArrowRight' && !onControl) || key === 'l' || key === 'L') {
      this.seekBy(10);
    } else if (key === 'ArrowUp' && !onControl) {
      this.changeVolume(0.1);
    } else if (key === 'ArrowDown' && !onControl) {
      this.changeVolume(-0.1);
    } else if (key === 'm' || key === 'M') {
      this.toggleMute();
    } else if (key === 'f' || key === 'F') {
      void this.toggleFullscreen();
    } else if (key === 'c' || key === 'C') {
      const subs = this.info()?.subtitles ?? [];
      this.selectSubtitle(this.subtitleId() === null ? pickSubtitle(subs, null) : null);
      this.say(this.subtitleId() === null ? 'Sous-titres désactivés' : 'Sous-titres activés');
    } else if (key === 'N' && e.shiftKey) {
      this.goNext();
    } else if (key === 'Escape') {
      if (this.panelOpen()) {
        this.panelOpen.set(false);
      } else if (this.nextIn() !== null) {
        this.cancelNext();
      } else if (!document.fullscreenElement) {
        void this.router.navigate(this.backLink());
      }
    } else {
      handled = false;
    }
    if (handled) {
      e.preventDefault();
      this.poke();
    }
  }

  private changeVolume(delta: number): void {
    const v = this.videoRef().nativeElement;
    v.volume = Math.max(0, Math.min(1, Math.round((v.volume + delta) * 20) / 20));
    v.muted = v.volume === 0;
    this.say('Volume ' + Math.round(v.volume * 100) + ' %');
  }

  /** Un toucher : commandes affichées ou masquées ; deux touchers à gauche ou à droite : ±10 s. */
  protected onTap(e: PointerEvent | MouseEvent): void {
    const el = e.currentTarget as HTMLElement;
    const x = e.clientX - el.getBoundingClientRect().left;
    const third = el.clientWidth / 3;
    if (this.tapTimer) {
      clearTimeout(this.tapTimer);
      this.tapTimer = undefined;
      if (x < third) this.seekBy(-10);
      else if (x > 2 * third) this.seekBy(10);
      else this.toggle();
      return;
    }
    this.tapTimer = setTimeout(() => {
      this.tapTimer = undefined;
      const type = (e as PointerEvent).pointerType;
      if (type === 'mouse' || type === undefined || type === '') {
        this.toggle();
      } else {
        this.controlsVisible.update((v) => !v);
        if (this.controlsVisible()) this.scheduleHide();
      }
    }, 250);
  }

  protected onPointer(): void {
    this.keyboard = false;
    this.poke();
  }

  protected poke(): void {
    this.controlsVisible.set(true);
    this.scheduleHide();
  }

  private scheduleHide(): void {
    clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => {
      // Navigation au clavier dans les commandes : elles restent affichées.
      const controls = this.rootRef().nativeElement.querySelector('.player-controls');
      const keyboardInside = this.keyboard && !!controls?.contains(document.activeElement);
      if (this.playing() && !this.panelOpen() && !keyboardInside) {
        this.controlsVisible.set(false);
      }
    }, HIDE_AFTER_MS);
  }

  protected onFullscreenChange(): void {
    this.fullscreen.set(!!document.fullscreenElement);
  }

  protected onVisibility(): void {
    if (document.visibilityState === 'hidden') this.saveOnExit();
  }

  // --- Progression --------------------------------------------------------------------------------------------------

  private report(): void {
    const v = this.videoRef().nativeElement;
    const pos = Math.floor(v.currentTime);
    const dur = Math.floor(this.duration());
    if (!reportable(pos, dur) || pos === this.lastReported) return;
    this.send(pos, dur);
  }

  private send(pos: number, dur: number): void {
    this.lastReported = pos;
    this.api.saveProgress(this.episodeId, pos, dur).subscribe({ error: () => (this.lastReported = -1) });
  }

  protected saveOnExit(): void {
    if (this.phase() !== 'ready') return;
    const pos = Math.floor(this.videoRef().nativeElement.currentTime);
    const dur = Math.floor(this.duration());
    if (reportable(pos, dur) && pos !== this.lastReported) {
      this.lastReported = pos;
      this.api.saveProgressOnExit(this.episodeId, pos, dur);
    }
  }

  // --- Nettoyage ----------------------------------------------------------------------------------------------------

  private detachMedia(): void {
    clearInterval(this.reportTimer);
    this.subs?.destroy();
    this.subs = null;
    this.hls?.destroy();
    this.hls = null;
    const v = this.videoRef().nativeElement;
    v.pause();
    v.removeAttribute('src');
    v.load();
    this.playing.set(false);
  }

  private teardown(save: boolean): void {
    if (save && this.episodeId) this.saveOnExit();
    clearTimeout(this.pollTimer);
    clearTimeout(this.hideTimer);
    clearTimeout(this.tapTimer);
    this.cancelNext();
    this.generation++;
    try {
      this.detachMedia();
    } catch {
      // vue pas encore créée ou déjà détruite
    }
    this.lastReported = -1;
    this.recovered = false;
  }

  private say(text: string): void {
    this.announce.set('');
    queueMicrotask(() => this.announce.set(text));
  }
}
