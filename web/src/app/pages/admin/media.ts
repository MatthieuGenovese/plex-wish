import { Component, DestroyRef, computed, inject, input, numberAttribute, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AndroidClass, MediaEntry } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatBytes, formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { WebPrepSection } from './web-prep';

const PAGE_SIZE = 50;

export const ANDROID_LABELS: Record<AndroidClass, string> = {
  DIRECT: 'Lisible sur Android',
  REMUX: 'Remux nécessaire',
  TRANSCODE: 'Transcodage nécessaire',
};

type Filter = 'pending' | 'failed' | AndroidClass | 'browser-ko' | 'browser-ok';
const FILTER_LABELS: Record<Filter, string> = {
  pending: 'Sans analyse',
  failed: 'Analyse en échec',
  DIRECT: ANDROID_LABELS.DIRECT,
  REMUX: ANDROID_LABELS.REMUX,
  TRANSCODE: ANDROID_LABELS.TRANSCODE,
  'browser-ko': 'Non lisible dans un navigateur',
  'browser-ok': 'Lisible dans un navigateur',
};

const WORKER_LABELS: Record<string, string> = {
  working: 'analyse en cours',
  remux: 'en pause pendant un remux demandé par un utilisateur',
  web: 'en pause pendant une préparation pour le navigateur',
  idle: 'à jour, en attente de nouveaux fichiers',
  scan: 'en pause pendant le scan',
  'remux-test': 'en pause pendant le test à blanc du remux',
  stopped: 'arrêtée',
};

const VARIANT_LABELS: Record<string, string> = {
  GENPTS: '-fflags +genpts',
  GENPTS_UNPACK: '+genpts et mpeg4_unpack_bframes',
};

/** Médias : analyse ffprobe (avancement, catégories, liste filtrable) et test à blanc du remux. ?filtre=&q=&page= */
@Component({
  selector: 'app-admin-media',
  imports: [Pager, WebPrepSection],
  template: `
    @let sum = summary();
    @if (sum.data; as s) {
      @if (!s.ffprobeVersion) {
        <div class="alert alert-error" role="alert"><p>ffprobe introuvable dans le conteneur du backend : pas d’analyse.</p></div>
      } @else if (!s.enabled) {
        <div class="alert alert-warning" role="status"><p>Analyse désactivée (<code>MEDIA_PROBE_ENABLED=false</code>) : les résultats déjà là restent affichés.</p></div>
      }
      <p class="muted" role="status" data-testid="media-status">
        {{ num(s.analyzed + s.failed) }} fichier{{ s.analyzed + s.failed > 1 ? 's' : '' }} analysé{{ s.analyzed + s.failed > 1 ? 's' : '' }}
        sur {{ num(s.files) }}{{ s.pending > 0 ? ', ' + num(s.pending) + ' en attente' : '' }} ·
        tâche {{ workerLabel(s.workerState) }}@if (s.ffprobeVersion) { · {{ s.ffprobeVersion }}}.
        Durée connue pour {{ num(s.episodesWithDuration) }} épisode{{ s.episodesWithDuration > 1 ? 's' : '' }} sur {{ num(s.episodes) }}.
      </p>
      <ul class="summary">
        <li class="tile"><span class="value">{{ num(s.android.DIRECT) }}</span><span>{{ androidLabels.DIRECT }}</span></li>
        <li class="tile" [class.warn]="s.android.REMUX > 0"><span class="value">{{ num(s.android.REMUX) }}</span><span>{{ androidLabels.REMUX }}</span></li>
        <li class="tile" [class.warn]="s.android.TRANSCODE > 0"><span class="value">{{ num(s.android.TRANSCODE) }}</span><span>{{ androidLabels.TRANSCODE }}</span></li>
        <li class="tile"><span class="value">{{ num(s.browserNotPlayable) }}</span><span>non lisibles dans un navigateur</span></li>
        <li class="tile" [class.warn]="s.failed > 0"><span class="value">{{ num(s.failed) }}</span><span>analyses en échec</span></li>
      </ul>
      <p class="disk" data-testid="media-remux-space">
        Remuxer tous les fichiers « remux nécessaire » ({{ num(s.remuxFiles) }}) : environ {{ bytes(s.remuxBytes) }} de copies
        (même taille que les originaux, sans ré-encodage).
      </p>
    }

    <section class="card remux" aria-labelledby="cache-title">
      <h2 id="cache-title">Remux à la demande</h2>
      <p class="muted">Les AVI et OGM sont convertis en MKV sans ré-encodage au premier lancement sur Android (« Préparation de
        l’épisode… »), un à la fois ; les demandes des utilisateurs passent avant tout le reste. Copies gardées dans le cache,
        les moins récemment lues effacées quand il est plein (jamais une copie en cours de lecture).</p>
      @if (cache().data; as c) {
        @if (!c.usable) {
          <div class="alert alert-error" role="alert"><p>Remux indisponible : {{ c.ffmpegVersion ? 'dossier du cache non accessible en écriture (' + c.cachePath + ', droits PUID/PGID)' : 'ffmpeg absent du conteneur' }}.</p></div>
        }
        <p role="status" data-testid="remux-cache">
          Cache : {{ bytes(c.usedBytes) }} sur {{ bytes(c.maxBytes) }} ({{ num(c.ready) }} copie{{ c.ready > 1 ? 's' : '' }})
          · {{ bytes(c.freeBytes) }} libres sur le volume · {{ num(c.queued) }} en file · {{ num(c.failed) }} en échec.
        </p>
        @if (c.queue.length > 0) {
          <ol class="queue">
            @for (q of c.queue; track q.mediaFileId) {
              <li>
                <span class="path">{{ q.path }}</span>
                <span class="muted small">
                  {{ q.status === 'RUNNING' ? 'en cours' + (q.progress !== null ? ' (' + pct(q.progress) + ')' : '') : 'en attente' }}
                  · {{ q.priority === 0 ? 'demandé par un utilisateur' : 'préparé à l’avance' }}
                  @if (q.blocked === 'CACHE_FULL') { · <span class="danger">cache plein : copies en cours de lecture</span> }
                </span>
              </li>
            }
          </ol>
        }
        @if (failures().length > 0) {
          <h3 class="sub">Remux impossibles</h3>
          <ul class="failures">
            @for (f of failures(); track f.mediaFileId) {
              <li>
                <span class="path">{{ f.path }}</span>
                <div class="small danger">{{ f.error }}</div>
                <div class="small muted">{{ f.attempts }} essai{{ f.attempts > 1 ? 's' : '' }}@if (f.nextAttemptAt) { · nouvel essai automatique après {{ time(f.nextAttemptAt) }} }</div>
                <button type="button" class="btn-small" (click)="retryRemux(f.mediaFileId)" [disabled]="busy()"
                        [attr.aria-label]="'Relancer le remux de ' + f.path">Relancer</button>
              </li>
            }
          </ul>
        }
        <div class="actions">
          @if (!confirmClear()) {
            <button type="button" class="btn-small" (click)="confirmClear.set(true)" [disabled]="c.ready === 0">Vider le cache…</button>
          } @else {
            <span role="alert">Effacer les copies prêtes (sauf celles en cours de lecture) ? Elles seront refaites à la demande.</span>
            <button type="button" (click)="confirmClear.set(false)">Annuler</button>
            <button type="button" class="btn-danger" (click)="clearCache()" [disabled]="busy()">Vider le cache</button>
          }
        </div>
      }
    </section>

    <app-admin-web-prep (changed)="reloads.update(n => n + 1)" />

    <section class="card remux" aria-labelledby="remux-title">
      <h2 id="remux-title">Test à blanc du remux</h2>
      <p class="muted">Chaque fichier « remux nécessaire » passe dans ffmpeg avec les deux commandes candidates, vers une sortie
        nulle : <strong>rien n’est écrit</strong>, mais tout le fichier est lu (charge disque). Un fichier à la fois, priorité basse ;
        l’analyse est en pause pendant ce temps. Arrêt possible ; relancé, il reprend où il s’était arrêté.</p>
      @if (test().data; as t) {
        <p role="status" data-testid="remux-test-status">
          @if (t.running && t.startAt) {
            Programmé pour {{ time(t.startAt) }}.
          } @else if (t.running) {
            En cours : {{ num(t.done) }} / {{ num(t.total) }} fichiers ({{ bytes(t.bytesDone) }} sur {{ bytes(t.bytesTotal) }})
            @if (t.estimatedSecondsLeft !== null) { · reste environ {{ hours(t.estimatedSecondsLeft) }} }
          } @else {
            {{ num(t.done) }} / {{ num(t.total) }} fichiers testés.
          }
          @if (t.lastError) { Erreur : {{ t.lastError }}. }
        </p>
        <ul class="variants">
          @for (v of variants; track v) {
            <li><code>{{ variantLabels[v] }}</code> :
              {{ num(t.perVariant[v]?.[0] ?? 0) }} réussite{{ (t.perVariant[v]?.[0] ?? 0) > 1 ? 's' : '' }},
              <span [class.danger]="(t.perVariant[v]?.[1] ?? 0) > 0">{{ num(t.perVariant[v]?.[1] ?? 0) }} échec{{ (t.perVariant[v]?.[1] ?? 0) > 1 ? 's' : '' }}</span>
            </li>
          }
        </ul>
        <div class="actions">
          @if (!t.running) {
            <button type="button" (click)="startTest()" [disabled]="busy()">Lancer maintenant</button>
            <label class="inline" for="m-at">Ou à</label>
            <input id="m-at" type="time" [value]="at()" (input)="at.set($any($event.target).value)" />
            <button type="button" (click)="startTest(at())" [disabled]="busy() || !at()">Programmer</button>
            @if (t.done > 0) {
              <button type="button" class="btn-small" (click)="resetTest()" [disabled]="busy()">Effacer les résultats</button>
            }
          } @else {
            <button type="button" class="btn-danger" (click)="stopTest()" [disabled]="busy()">Arrêter</button>
          }
        </div>
      }
    </section>

    <form class="filters" (submit)="$event.preventDefault()">
      <div class="field">
        <label for="m-filter">Afficher</label>
        <select id="m-filter" (change)="navigate({ filtre: $any($event.target).value || null })">
          <option value="" [selected]="!filtre()">Tous les fichiers</option>
          @for (f of filters; track f) { <option [value]="f" [selected]="filtre() === f">{{ filterLabels[f] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="m-q">Fichier</label>
        <input id="m-q" type="search" [value]="q() ?? ''" autocomplete="off" (input)="onSearch($any($event.target).value)" />
      </div>
      @if (filtre() === 'failed') {
        <button type="button" (click)="reprobeFailed()" [disabled]="busy()">Réanalyser les échecs</button>
      }
    </form>

    <div aria-live="polite">
      @if (message(); as m) {
        <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
      }
    </div>

    @let l = list();
    @if (l.error) {
      <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
    } @else if (l.data; as page) {
      <p class="muted">{{ num(page.total) }} fichier{{ page.total > 1 ? 's' : '' }}</p>
      @if (page.items.length > 0) {
        <div class="table-wrap">
          <table class="wide">
            <thead>
              <tr><th scope="col">Fichier</th><th scope="col">Durée</th><th scope="col">Pistes</th><th scope="col">Android</th>
                <th scope="col">Navigateur</th><th scope="col">Test remux</th><th scope="col"><span class="visually-hidden">Action</span></th></tr>
            </thead>
            <tbody>
              @for (e of page.items; track e.mediaFileId) {
                <tr>
                  <th scope="row"><span class="path">{{ e.path }}</span>
                    <div class="muted small">{{ e.extension.toUpperCase() }} · {{ bytes(e.fileSize) }}</div>
                    @if (e.status === 'FAILED') { <div class="small danger">Échec : {{ e.error }}</div> }
                    @if (e.status === 'PENDING') { <div class="small muted">En attente d’analyse</div> }
                  </th>
                  <td class="nowrap">{{ minutes(e.durationSeconds) }}</td>
                  <td class="small tracks">
                    @if (e.video) { <div><span class="muted">Vidéo</span> {{ e.video }}</div> }
                    @if (e.audio) { <div><span class="muted">Audio</span> {{ e.audio }}</div> }
                    @if (e.subtitles) { <div><span class="muted">Sous-titres</span> {{ e.subtitles }}</div> }
                  </td>
                  <td>
                    @if (e.android) {
                      <span class="badge" [class.badge-success]="e.android === 'DIRECT'" [class.badge-warning]="e.android === 'REMUX'"
                            [class.badge-danger]="e.android === 'TRANSCODE'">{{ androidLabels[e.android] }}</span>
                      @if (e.androidReasons) { <div class="muted small">{{ e.androidReasons }}</div> }
                    }
                  </td>
                  <td class="small">
                    @if (e.browserPlayable === true) { <span class="badge badge-success">Lisible</span> }
                    @else if (e.browserPlayable === false) { <span class="badge">Non lisible</span><div class="muted">{{ e.browserReasons }}</div> }
                  </td>
                  <td class="small tests">
                    @for (r of e.remuxTest; track r.variant) {
                      <div><span [class.danger]="!r.ok">{{ r.ok ? '✓' : '✗' }}</span> <code>{{ variantLabels[r.variant] }}</code>
                        @if (!r.ok) { <span class="muted">{{ r.timedOut ? 'délai dépassé' : 'code ' + r.exitCode }}{{ r.message ? ' : ' + r.message : '' }}</span> }
                      </div>
                    }
                  </td>
                  <td>
                    @if (e.remuxStatus) {
                      <div class="small"><span class="badge" [class.badge-success]="e.remuxStatus === 'READY'"
                        [class.badge-danger]="e.remuxStatus === 'FAILED'">{{ remuxLabels[e.remuxStatus] ?? e.remuxStatus }}</span></div>
                    }
                    <div class="row-actions">
                      <button type="button" class="btn-small" (click)="reprobe(e)" [disabled]="busy()"
                              [attr.aria-label]="'Réanalyser ' + e.path">Réanalyser</button>
                      @if (e.browserPlayable === false && e.animeId !== null) {
                        <button type="button" class="btn-small" (click)="prepareWeb(e)" [disabled]="busy()"
                                [attr.aria-label]="'Préparer pour le navigateur tous les épisodes de ' + (e.animeTitle ?? e.path)">Préparer pour le navigateur</button>
                      }
                      @if (e.android === 'REMUX' && e.animeId !== null) {
                        <button type="button" class="btn-small" (click)="prepare(e)" [disabled]="busy()"
                                [attr.aria-label]="'Préparer à l’avance les épisodes à remuxer de ' + (e.animeTitle ?? e.path)">Préparer l’animé</button>
                      }
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages des médias" (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .summary { display: grid; grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr)); gap: var(--space-2); margin: 0 0 var(--space-3); padding: 0; list-style: none; }
    .tile { display: flex; flex-direction: column; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-surface); }
    .value { font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; }
    .warn .value { color: var(--color-warning); }
    .disk { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    .remux { margin: var(--space-5) 0; }
    .remux h2 { font-size: var(--font-size-lg); }
    .variants { margin: 0 0 var(--space-3); padding-left: var(--space-5); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: center; }
    .inline { margin: 0; }
    .queue, .failures { margin: 0 0 var(--space-3); padding-left: var(--space-5); }
    .failures li { margin-bottom: var(--space-2); }
    .sub { font-size: var(--font-size-md); }
    .row-actions { display: flex; flex-direction: column; gap: var(--space-1); }
    .actions input { width: auto; }
    .tracks { min-width: 15rem; }
    .tests { min-width: 12rem; }
    th[scope='row'] { min-width: 14rem; }
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: flex-end; }
    .filters .field { flex: 1 1 12rem; max-width: 22rem; }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .path { overflow-wrap: anywhere; }
    .small { font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    .nowrap { white-space: nowrap; }
    .danger { color: var(--color-danger); }
  `,
})
export class MediaPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  readonly filtre = input<Filter | undefined, unknown>(undefined, {
    transform: (v: unknown) => (Object.keys(FILTER_LABELS).includes(v as string) ? (v as Filter) : undefined),
  });
  readonly q = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly filters = Object.keys(FILTER_LABELS) as Filter[];
  protected readonly filterLabels = FILTER_LABELS;
  protected readonly androidLabels = ANDROID_LABELS;
  protected readonly variantLabels = VARIANT_LABELS;
  protected readonly variants = Object.keys(VARIANT_LABELS);
  protected readonly num = formatNumber;
  protected readonly bytes = formatBytes;
  protected readonly time = formatDateTime;
  protected readonly busy = signal(false);
  protected readonly at = signal('02:00');
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  protected readonly reloads = signal(0);
  private readonly webPrep = viewChild(WebPrepSection);
  protected readonly summary = loadOn(this.reloads, () => this.api.mediaSummary());
  protected readonly test = loadOn(this.reloads, () => this.api.remuxTest());
  protected readonly cache = loadOn(this.reloads, () => this.api.remuxSummary());
  private readonly failedJobs = loadOn(this.reloads, () => this.api.remuxJobs('FAILED'));
  protected readonly failures = computed(() => this.failedJobs().data ?? []);
  protected readonly confirmClear = signal(false);
  protected readonly remuxLabels: Record<string, string> = {
    QUEUED: 'Remux en attente', RUNNING: 'Remux en cours', READY: 'Copie prête', FAILED: 'Remux impossible',
  };
  private readonly query = computed(() => ({
    filter: this.filtre(),
    q: this.q()?.trim(),
    page: this.page() - 1,
    size: PAGE_SIZE,
    n: this.reloads(),
  }));
  protected readonly list = loadOn(this.query, ({ n: _n, ...q }) => this.api.mediaFiles(q));

  constructor() {
    // Avancement rafraîchi toutes les 15 s pendant l'analyse ou le test à blanc.
    const timer = setInterval(() => {
      const s = this.summary().data;
      if (this.test().data?.running || (this.cache().data?.queued ?? 0) > 0 || (s && s.pending > 0 && s.workerState === 'working')) {
        this.reloads.update((n) => n + 1);
      }
    }, 15_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  workerLabel(state: string): string {
    return WORKER_LABELS[state] ?? state;
  }

  minutes(s: number | null): string {
    if (s === null) return '—';
    const m = Math.floor(s / 60);
    return `${m} min ${String(Math.round(s % 60)).padStart(2, '0')}`;
  }

  hours(s: number): string {
    return s < 3600 ? `${Math.max(1, Math.round(s / 60))} min` : `${Math.floor(s / 3600)} h ${String(Math.round((s % 3600) / 60)).padStart(2, '0')}`;
  }

  navigate(params: Record<string, string | null>): void {
    this.router.navigate([], { queryParams: { ...params, page: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.navigate({ q: value.trim() || null }), 300);
  }

  goToPage(page: number): void {
    this.router.navigate([], { queryParams: { page: page === 0 ? null : page + 1 }, queryParamsHandling: 'merge' });
  }

  /** Heure « HH:MM » → prochain instant correspondant (aujourd'hui ou demain), heure locale du navigateur. */
  static nextOccurrence(hhmm: string, now = new Date()): Date {
    const [h, m] = hhmm.split(':').map(Number);
    const d = new Date(now);
    d.setHours(h, m, 0, 0);
    if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1);
    return d;
  }

  startTest(at?: string): void {
    const when = at ? MediaPage.nextOccurrence(at) : undefined;
    this.run(this.api.startRemuxTest(when?.toISOString()),
      when ? `Test à blanc programmé pour ${formatDateTime(when.toISOString())}.` : 'Test à blanc lancé.');
  }

  stopTest(): void {
    this.run(this.api.stopRemuxTest(), 'Test à blanc arrêté ; relancé, il reprendra où il s’est arrêté.');
  }

  resetTest(): void {
    this.run(this.api.resetRemuxTest(), 'Résultats du test à blanc effacés.');
  }

  reprobe(e: MediaEntry): void {
    this.run(this.api.reprobe(e.mediaFileId), `« ${e.path} » sera réanalysé au prochain passage.`);
  }

  pct(p: number): string {
    return `${Math.round(p * 100)} %`;
  }

  retryRemux(id: number): void {
    this.run(this.api.retryRemux(id), 'Remux remis en file.');
  }

  prepare(e: MediaEntry): void {
    this.run(this.api.prepareAnime(e.animeId!), `« ${e.animeTitle ?? e.path} » : épisodes à remuxer mis en file (après les demandes des utilisateurs).`);
  }

  prepareWeb(e: MediaEntry): void {
    this.api.prepareAnimeForBrowser(e.animeId!).subscribe({
      next: (r) => {
        this.message.set({ text: `« ${e.animeTitle ?? e.path} » : ${r.queued} épisode${r.queued > 1 ? 's' : ''} sur ${r.episodes} mis en file pour le navigateur `
          + '(conversion si besoin ; en pause pendant les lectures).', error: false });
        this.reloads.update((n) => n + 1);
        this.webPrep()?.reload();
      },
      error: (err: unknown) => this.message.set({ text: errorMessage(err), error: true }),
    });
  }

  clearCache(): void {
    this.confirmClear.set(false);
    this.run(this.api.clearRemuxCache(), 'Cache vidé (les copies en cours de lecture sont gardées).');
  }

  reprobeFailed(): void {
    this.run(this.api.reprobeFailed(), 'Les analyses en échec seront refaites.');
  }

  private run(obs: import('rxjs').Observable<unknown>, success: string): void {
    this.busy.set(true);
    obs.subscribe({
      next: () => {
        this.busy.set(false);
        this.message.set({ text: success, error: false });
        this.reloads.update((n) => n + 1);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }
}
