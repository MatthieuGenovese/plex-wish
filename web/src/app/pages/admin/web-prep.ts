import { Component, DestroyRef, effect, inject, output, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { WebJobItem, WebJobKind, WebJobLabel, WebRunning } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatBytes, formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';

const PHASES: Record<string, string> = {
  PROBE: 'analyse',
  SUBS: 'sous-titres',
  HLS: 'copie sans conversion',
  CONVERT: 'conversion de la vidéo',
  AUDIO: 'conversion du son',
  VERIFY: 'vérification',
};

const PRIORITIES = ['demandé par un utilisateur', 'demandé par l’admin', 'préventif (la nuit)'];

/**
 * Administration › Médias › « Lecteur web » (10.3, docs/WEB-PLAYER.md §5) : réglages des conversions, place prise par
 * le cache, préparations en cours (avec la vitesse), en file et en échec ; relancer, annuler, nettoyer.
 */
@Component({
  selector: 'app-admin-web-prep',
  template: `
    <section class="card web" aria-labelledby="web-title">
      <h2 id="web-title">Lecteur web</h2>
      <p class="muted">Pour le navigateur, le serveur prépare chaque épisode à la première lecture : sous-titres, copie sans
        conversion, et <strong>conversion</strong> quand la vidéo ou le son n’est lisible par aucun navigateur (H.264 10 bits, Xvid,
        AC3, DTS…). Une conversion à la fois, priorité basse, 2 fils de calcul ; celles que personne n’attend devant un écran
        sont <strong>mises en pause pendant les lectures</strong>.</p>
      @let o = overview();
      @if (o.data; as d) {
        @if (!d.usable) {
          <div class="alert alert-error" role="alert"><p>Préparation indisponible : {{ d.ffmpegVersion ? 'dossier du cache non accessible en écriture' : 'ffmpeg absent du conteneur' }}.</p></div>
        }
        <p role="status" data-testid="web-cache-status">
          Cache : {{ bytes(d.cache.usedBytes) }} sur {{ bytes(d.cache.capBytes) }}
          ({{ num(d.cache.readyBase) }} préparation{{ d.cache.readyBase > 1 ? 's' : '' }}, {{ num(d.cache.readyConverted) }}
          conversion{{ d.cache.readyConverted > 1 ? 's' : '' }}) · {{ bytes(d.cache.freeBytes) }} libres sur le volume
          @if (d.cache.hostPath) { ({{ d.cache.hostPath }}) } · {{ num(d.queued) }} en file.
        </p>

        @if (d.running.length > 0) {
          <h3 class="sub">En cours</h3>
          <ul class="list" data-testid="web-running">
            @for (r of d.running; track r.kind) {
              <li>
                <span>{{ label(r.episode) }}</span>
                <span class="muted small"> · {{ phase(r) }}@if (r.progress !== null) { ({{ pct(r.progress) }}) }
                  @if (r.speed) { · {{ speed(r.speed) }} } · {{ priority(r.priority) }}
                  @if (r.paused) { · <strong>en pause : lecture en cours</strong> }</span>
                @if (r.kind === 'CONV') {
                  <button type="button" class="btn-small" (click)="cancel(r.mediaFileId, r.kind, r.episode)" [disabled]="busy()"
                          [attr.aria-label]="'Annuler la conversion de ' + label(r.episode)">Annuler</button>
                }
              </li>
            }
          </ul>
        }
        @if (d.queue.length > 0) {
          <h3 class="sub">En file</h3>
          <ol class="list" data-testid="web-queue">
            @for (q of d.queue; track q.kind + q.mediaFileId) {
              <li>
                <span>{{ label(q.episode) }}</span>
                <span class="muted small"> · {{ q.kind === 'CONV' ? 'conversion' : 'préparation' }} · {{ priority(q.priority) }}
                  @if (q.blocked) { · <span class="danger">cache plein</span> }</span>
                <button type="button" class="btn-small" (click)="cancel(q.mediaFileId, q.kind, q.episode)" [disabled]="busy()"
                        [attr.aria-label]="'Retirer de la file : ' + label(q.episode)">Retirer</button>
              </li>
            }
          </ol>
        }
        @if (d.failures.length > 0) {
          <h3 class="sub">En échec</h3>
          <ul class="list failures" data-testid="web-failures">
            @for (f of d.failures; track f.kind + f.mediaFileId) {
              <li>
                <span>{{ label(f.episode) }}</span> <span class="muted small">({{ f.kind === 'CONV' ? 'conversion' : 'préparation' }})</span>
                <div class="small danger">{{ f.error }}</div>
                <div class="small muted">{{ f.attempts }} essai{{ f.attempts > 1 ? 's' : '' }}
                  @if (f.nextAttemptAt) { · nouvel essai automatique après {{ time(f.nextAttemptAt) }} } @else { · plus d’essai automatique }</div>
                <button type="button" class="btn-small" (click)="retry(f)" [disabled]="busy()"
                        [attr.aria-label]="'Relancer : ' + label(f.episode)">Relancer</button>
              </li>
            }
          </ul>
        }

        <form class="settings" (submit)="$event.preventDefault(); save()" aria-labelledby="web-settings-title">
          <h3 class="sub" id="web-settings-title">Réglages des conversions</h3>
          <fieldset>
            <legend>Hauteur maximale de l’image convertie</legend>
            <label class="choice"><input type="radio" name="web-height" [checked]="height() === 720" (change)="height.set(720)" />
              720p (conseillé : plus rapide, lecture possible pendant la conversion)</label>
            <label class="choice"><input type="radio" name="web-height" [checked]="height() === 1080" (change)="height.set(1080)" />
              1080p (meilleure image, environ 1,5 fois plus lent)</label>
          </fieldset>
          <label class="choice"><input type="checkbox" [checked]="preventive()" (change)="preventive.set($any($event.target).checked)" />
            Préparer la nuit ({{ d.settings.nightStartHour }} h – {{ d.settings.nightEndHour }} h) les 2 épisodes suivants des animés en cours et
            les ajouts récents</label>
          <label class="choice"><input type="checkbox" [checked]="preventiveVideo()" [disabled]="!preventive()"
                 (change)="preventiveVideo.set($any($event.target).checked)" />
            Convertir aussi la vidéo la nuit (plusieurs heures de calcul ; sinon seulement le son et les copies sans conversion)</label>
          <div class="actions">
            <button type="submit" [disabled]="busy()">Enregistrer</button>
            <button type="button" class="btn-small" (click)="cleanup()" [disabled]="busy()">Nettoyer le cache maintenant</button>
          </div>
          @if (speeds(d.speeds); as s) { <p class="muted small" data-testid="web-speeds">Vitesses observées sur ce serveur : {{ s }}.</p> }
        </form>
      } @else if (o.error) {
        <div class="alert alert-error" role="alert"><p>{{ o.error }}</p></div>
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
      <div aria-live="polite">
        @if (message(); as m) {
          <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
        }
      </div>
    </section>
  `,
  styles: `
    .web { margin: var(--space-5) 0; }
    .web h2 { font-size: var(--font-size-lg); }
    .sub { font-size: var(--font-size-md); }
    .list { margin: 0 0 var(--space-3); padding-left: var(--space-5); }
    .list li { margin-bottom: var(--space-2); }
    .list button { margin-left: var(--space-2); }
    .small { font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    .danger { color: var(--color-danger); }
    .settings fieldset { border: 0; margin: 0 0 var(--space-2); padding: 0; }
    .settings legend { font-weight: var(--font-weight-medium); margin-bottom: var(--space-1); }
    .choice { display: flex; gap: var(--space-2); align-items: flex-start; margin: var(--space-1) 0; font-weight: normal; }
    .choice input { margin-top: 0.2em; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: center; margin-top: var(--space-2); }
  `,
})
export class WebPrepSection {
  private readonly api = inject(AdminApi);
  /** Une action a changé l'état (la page parente peut rafraîchir ses chiffres). */
  readonly changed = output<void>();

  private readonly reloads = signal(0);
  protected readonly overview = loadOn(this.reloads, () => this.api.webOverview());
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly height = signal<number>(720);
  protected readonly preventive = signal(true);
  protected readonly preventiveVideo = signal(false);
  protected readonly bytes = formatBytes;
  protected readonly num = formatNumber;
  protected readonly time = formatDateTime;
  private loaded = false;

  constructor() {
    // Réglages recopiés dans le formulaire au premier chargement.
    effect(() => {
      const st = this.overview().data?.settings;
      if (st && !this.loaded) {
        this.loaded = true;
        this.height.set(st.maxHeight);
        this.preventive.set(st.preventive);
        this.preventiveVideo.set(st.preventiveVideo);
      }
    });
    // État rafraîchi toutes les 10 s quand quelque chose tourne ou attend.
    const timer = setInterval(() => {
      const d = this.overview().data;
      if (d && (d.running.length > 0 || d.queued > 0)) this.reload();
    }, 10_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  reload(): void {
    this.reloads.update((n) => n + 1);
  }

  label(e: WebJobLabel): string {
    if (!e.animeTitle) return 'Fichier sans épisode';
    const season = e.seasonNumber === 0 ? 'Spéciaux' : 'S' + e.seasonNumber;
    return `${e.animeTitle} · ${season} · ép. ${e.episodeNumber}`;
  }

  phase(r: WebRunning): string {
    return (r.phase && PHASES[r.phase]) ?? (r.kind === 'CONV' ? 'conversion' : 'préparation');
  }

  priority(p: number): string {
    return PRIORITIES[Math.max(0, Math.min(2, p))];
  }

  pct(p: number): string {
    return `${Math.round(p * 100)} %`;
  }

  speed(s: number): string {
    return `${s.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} × le temps réel`;
  }

  speeds(s: Record<string, number>): string | null {
    const names: Record<string, string> = { audio: 'son seul', videosd: 'vidéo SD', video720: 'vidéo 720p', video1080: 'vidéo 1080p' };
    const parts = Object.entries(s).filter(([k]) => names[k]).map(([k, v]) => `${names[k]} ${this.speed(v)}`);
    return parts.length ? parts.join(', ') : null;
  }

  save(): void {
    this.run(this.api.saveWebSettings({ maxHeight: this.height(), preventive: this.preventive(), preventiveVideo: this.preventiveVideo() }),
      'Réglages enregistrés. Une nouvelle hauteur s’applique aux prochaines conversions.');
  }

  retry(f: WebJobItem): void {
    this.run(this.api.retryWebJob(f.mediaFileId, f.kind), `${this.label(f.episode)} : remis en file.`);
  }

  cancel(id: number, kind: WebJobKind, e: WebJobLabel): void {
    this.run(this.api.cancelWebJob(id, kind), `${this.label(e)} : ${kind === 'CONV' ? 'conversion annulée' : 'retiré de la file'}.`);
  }

  cleanup(): void {
    this.busy.set(true);
    this.api.cleanupWebCache().subscribe({
      next: (r) => this.done(`Cache nettoyé : ${r.orphans} préparation(s) dont la source a changé, ${r.evicted} ancienne(s) effacée(s).`),
      error: (err: unknown) => this.failed(err),
    });
  }

  private run(obs: Observable<unknown>, success: string): void {
    this.busy.set(true);
    obs.subscribe({ next: () => this.done(success), error: (err: unknown) => this.failed(err) });
  }

  private done(text: string): void {
    this.busy.set(false);
    this.message.set({ text, error: false });
    this.reload();
    this.changed.emit();
  }

  private failed(err: unknown): void {
    this.busy.set(false);
    this.message.set({ text: errorMessage(err), error: true });
  }
}
