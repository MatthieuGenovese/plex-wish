import { Component, computed, inject, input, numberAttribute, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PosterEntry, PosterFilter } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatBytes, formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { Poster } from '../../shared/poster';

const PAGE_SIZE = 50;

export const FILTER_LABELS: Record<PosterFilter, string> = {
  local: 'Sur le NAS',
  remote: 'Distantes',
  missing: 'Sans affiche',
  failed: 'En échec',
};

/** Affiches sur le NAS : combien sont locales, distantes ou absentes, place disque, retéléchargement. ?filtre=&q=&page= */
@Component({
  selector: 'app-admin-posters',
  imports: [Pager, Poster, RouterLink],
  template: `
    @let sum = summary();
    @if (sum.data; as s) {
      @if (!s.enabled) {
        <div class="alert alert-warning" role="status"><p>Téléchargement désactivé (<code>POSTERS_ENABLED=false</code>) : affiches chargées depuis TMDB / AniList.</p></div>
      } @else if (!s.folderUsable) {
        <div class="alert alert-error" role="alert">
          <p>Le dossier des affiches (<code>{{ s.folder }}</code> dans le conteneur) est absent ou non accessible en écriture :
            les affiches restent chargées depuis TMDB / AniList. Vérifier <code>POSTERS_HOST_PATH</code> et les droits PUID/PGID (README).</p>
        </div>
      } @else if (s.pausedUntil) {
        <p class="muted" role="status">Serveur d’images indisponible ({{ s.lastUnavailable }}) : reprise vers {{ time(s.pausedUntil) }}.</p>
      }
      <ul class="summary">
        @for (f of filters; track f) {
          <li>
            <a class="tile" [class.selected]="filtre() === f" [class.warn]="f === 'failed' && s.failed > 0"
               [routerLink]="[]" [queryParams]="{ filtre: filtre() === f ? null : f, page: null }" queryParamsHandling="merge">
              <span class="value">{{ num(count(s, f)) }}</span><span>{{ labels[f] }}</span>
            </a>
          </li>
        }
      </ul>
      <p class="disk" data-testid="disk">
        Sur le NAS : {{ num(s.localTmdb) }} TMDB, {{ num(s.localAniList) }} AniList · {{ bytes(s.diskBytes) }} utilisés,
        environ {{ bytes(s.estimatedBytes) }} quand tout sera téléchargé ({{ bytes(s.averageBytes) }} par affiche)
        @if (s.freeBytes >= 0) { · {{ bytes(s.freeBytes) }} libres }
      </p>
    }

    <form class="filters" (submit)="$event.preventDefault()">
      <div class="field">
        <label for="p-q">Animé</label>
        <input id="p-q" type="search" [value]="q() ?? ''" autocomplete="off" (input)="onSearch($any($event.target).value)" />
      </div>
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
      <p class="muted">{{ num(page.total) }} animé{{ page.total > 1 ? 's' : '' }}</p>
      @if (page.items.length > 0) {
        <div class="table-wrap">
          <table class="wide">
            <thead>
              <tr>
                <th scope="col"><span class="visually-hidden">Affiche</span></th><th scope="col">Animé</th>
                <th scope="col">Affiche</th><th scope="col">Source</th>
                <th scope="col"><span class="visually-hidden">Action</span></th>
              </tr>
            </thead>
            <tbody>
              @for (e of page.items; track e.animeId) {
                <tr>
                  <td class="thumb"><app-poster [title]="e.title" [url]="e.posterUrl" /></td>
                  <th scope="row">{{ e.title }}</th>
                  <td>
                    <span class="badge" [class.badge-success]="e.state === 'LOCAL'" [class.badge-warning]="e.state === 'REMOTE'"
                          [class.badge-danger]="e.state === 'MISSING'">{{ stateLabel(e) }}</span>
                    @if (e.failed) { <span class="badge badge-danger">échec</span> }
                    @if (e.bytes !== null) { <div class="muted small">{{ bytes(e.bytes) }} · {{ time(e.fetchedAt) }}</div> }
                    @if (e.lastError) { <div class="muted small">{{ e.lastError }}</div> }
                  </td>
                  <td class="nowrap">{{ e.provider === 'TMDB' ? 'TMDB' : e.provider === 'ANILIST' ? 'AniList' : '—' }}</td>
                  <td>
                    @if (e.sourceUrl) {
                      <button type="button" class="btn-small" (click)="redownload(e)" [disabled]="busy()"
                              [attr.aria-label]="'Retélécharger l’affiche de ' + e.title">Retélécharger</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages des affiches" (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .summary { display: grid; grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr)); gap: var(--space-2); margin: 0 0 var(--space-3); padding: 0; list-style: none; }
    .tile { display: flex; flex-direction: column; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); color: var(--color-text); text-decoration: none; height: 100%; }
    .tile:hover { background: var(--color-surface-raised); color: var(--color-text); }
    .tile.selected { border-color: var(--color-accent); box-shadow: inset 0 0 0 1px var(--color-accent); }
    .value { font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; }
    .warn .value { color: var(--color-warning); }
    .disk { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    .filters .field { max-width: 22rem; }
    .thumb { width: 3.5rem; }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .small { font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    .nowrap { white-space: nowrap; }
  `,
})
export class PostersPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  readonly filtre = input<PosterFilter | undefined, unknown>(undefined, {
    transform: (v: unknown) => (Object.keys(FILTER_LABELS).includes(v as string) ? (v as PosterFilter) : undefined),
  });
  readonly q = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly filters: PosterFilter[] = ['local', 'remote', 'missing', 'failed'];
  protected readonly labels = FILTER_LABELS;
  protected readonly num = formatNumber;
  protected readonly bytes = formatBytes;
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  private readonly reloads = signal(0);
  protected readonly summary = loadOn(this.reloads, () => this.api.posterSummary());
  private readonly query = computed(() => ({
    filter: this.filtre(),
    q: this.q()?.trim(),
    page: this.page() - 1,
    size: PAGE_SIZE,
    n: this.reloads(),
  }));
  protected readonly list = loadOn(this.query, ({ n: _n, ...q }) => this.api.posters(q));

  count(s: { local: number; remote: number; missing: number; failed: number }, f: PosterFilter): number {
    return s[f];
  }

  stateLabel(e: PosterEntry): string {
    return e.state === 'LOCAL' ? 'sur le NAS' : e.state === 'REMOTE' ? 'distante' : 'aucune';
  }

  time(iso: string | null): string {
    return iso ? formatDateTime(iso) : '';
  }

  onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.router.navigate([], {
      queryParams: { q: value.trim() || null, page: null }, queryParamsHandling: 'merge', replaceUrl: true,
    }), 300);
  }

  goToPage(page: number): void {
    this.router.navigate([], { queryParams: { page: page === 0 ? null : page + 1 }, queryParamsHandling: 'merge' });
  }

  redownload(e: PosterEntry): void {
    this.busy.set(true);
    this.api.redownloadPoster(e.animeId).subscribe({
      next: ({ running }) => {
        this.busy.set(false);
        this.message.set({
          text: running
            ? `« ${e.title} » : affiche remise en file, téléchargée dans quelques secondes.`
            : `« ${e.title} » : affiche remise en file, mais la tâche de téléchargement est arrêtée (voir l’avertissement ci-dessus).`,
          error: false,
        });
        this.reloads.update((n) => n + 1);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }
}
