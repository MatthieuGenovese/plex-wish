import { Component, computed, inject, input, numberAttribute, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CastAdminEntry, CastStatus } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatBytes, formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';

const PAGE_SIZE = 50;

export const CAST_STATUS_LABELS: Record<CastStatus, string> = {
  OK: 'Distribution récupérée',
  PENDING: 'En attente',
  NONE: 'Aucune chez AniList',
  FAILED: 'En échec',
  EXCLUDED: 'Exclu (adulte)',
  NO_MATCH: 'Pas d’appariement AniList',
};

type Filter = 'missing' | 'failed' | 'ok';
const FILTER_LABELS: Record<Filter, string> = { missing: 'Sans distribution', failed: 'En échec', ok: 'Avec distribution' };

/** Distribution : avancement, place disque, animés sans distribution, relance, effacement. ?filtre=&q=&page= */
@Component({
  selector: 'app-admin-cast',
  imports: [Pager, RouterLink],
  template: `
    @let sum = summary();
    @if (sum.data; as s) {
      @if (!s.enabled) {
        <div class="alert alert-warning" role="status"><p>Récupération désactivée (<code>CAST_ENABLED=false</code>) : la distribution déjà là reste affichée.</p></div>
      } @else if (!s.folderUsable) {
        <div class="alert alert-error" role="alert"><p>Le dossier des photos n’est pas accessible en écriture : photos chargées depuis AniList (voir « Affiches sur le NAS » dans le README).</p></div>
      }
      <p class="muted" role="status" data-testid="cast-status">
        {{ num(s.counts.OK) }} animé{{ s.counts.OK > 1 ? 's' : '' }} sur {{ num(s.withAniList) }} appariés à AniList ont leur distribution
        ({{ s.maxRoles }} rôles au plus par animé).
        @if (s.counts.PENDING > 0) {
          {{ num(s.counts.PENDING) }} en attente{{ s.waitingForMetadata ? ', après les métadonnées (prioritaires)' : '' }}.
        }
        @if (s.pausedUntil) { AniList indisponible ({{ s.lastUnavailable }}) : reprise vers {{ time(s.pausedUntil) }}. }
      </p>
      <ul class="summary">
        <li class="tile"><span class="value">{{ num(s.people) }}</span><span>comédiens</span></li>
        <li class="tile"><span class="value">{{ num(s.roles) }}</span><span>rôles</span></li>
        <li class="tile"><span class="value">{{ num(s.images.OK) }}</span><span>photos sur le NAS</span></li>
        <li class="tile" [class.warn]="s.images.FAILED > 0"><span class="value">{{ num(s.images.FAILED) }}</span><span>photos refusées</span></li>
      </ul>
      <p class="disk" data-testid="cast-disk">
        {{ bytes(s.diskBytes) }} utilisés · environ {{ bytes(s.estimatedBytes) }} une fois tout récupéré
        @if (s.images.PENDING > 0) { · {{ num(s.images.PENDING) }} photo{{ s.images.PENDING > 1 ? 's' : '' }} à télécharger }
      </p>
    }

    <form class="filters" (submit)="$event.preventDefault()">
      <div class="field">
        <label for="c-filter">Afficher</label>
        <select id="c-filter" (change)="navigate({ filtre: $any($event.target).value || null })">
          <option value="" [selected]="!filtre()">Tous les animés</option>
          @for (f of filters; track f) { <option [value]="f" [selected]="filtre() === f">{{ filterLabels[f] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="c-q">Animé</label>
        <input id="c-q" type="search" [value]="q() ?? ''" autocomplete="off" (input)="onSearch($any($event.target).value)" />
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
              <tr><th scope="col">Animé</th><th scope="col">État</th><th scope="col">Rôles</th><th scope="col">Saisons</th>
                <th scope="col"><span class="visually-hidden">Action</span></th></tr>
            </thead>
            <tbody>
              @for (e of page.items; track e.animeId) {
                <tr>
                  <th scope="row"><a [routerLink]="['/anime', e.animeId]">{{ e.title }}</a></th>
                  <td>
                    <span class="badge" [class.badge-success]="e.status === 'OK'" [class.badge-danger]="e.status === 'FAILED'"
                          [class.badge-warning]="e.status === 'PENDING' || e.status === 'NO_MATCH'">{{ statusLabels[e.status] }}</span>
                    @if (e.fetchedAt) { <div class="muted small">{{ time(e.fetchedAt) }}</div> }
                    @if (e.lastError) { <div class="muted small">{{ e.lastError }}</div> }
                  </td>
                  <td class="nowrap">{{ e.roles }}</td>
                  <td class="nowrap">{{ e.seasons }}</td>
                  <td>
                    @if (e.status !== 'NO_MATCH') {
                      <button type="button" class="btn-small" (click)="refresh(e)" [disabled]="busy()"
                              [attr.aria-label]="'Relancer la distribution de ' + e.title">Relancer</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages de la distribution" (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }

    <section class="card purge" aria-labelledby="c-purge">
      <h2 id="c-purge">Effacer toute la distribution</h2>
      <p class="muted">Efface les personnages, les comédiens et leurs photos (garde-fou si les conditions d’AniList l’exigeaient).
        La récupération recommence ensuite, sauf avec <code>CAST_ENABLED=false</code>.</p>
      @if (!confirmPurge()) {
        <button type="button" class="btn-danger" (click)="confirmPurge.set(true)">Effacer toute la distribution…</button>
      } @else {
        <p role="alert"><strong>Tout effacer ?</strong> Données et images, définitivement.</p>
        <div class="actions">
          <button type="button" (click)="confirmPurge.set(false)">Annuler</button>
          <button type="button" class="btn-danger" (click)="purge()" [disabled]="busy()">Confirmer l’effacement</button>
        </div>
      }
    </section>
  `,
  styles: `
    .summary { display: grid; grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr)); gap: var(--space-2); margin: 0 0 var(--space-3); padding: 0; list-style: none; }
    .tile { display: flex; flex-direction: column; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-surface); }
    .value { font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; }
    .warn .value { color: var(--color-warning); }
    .disk { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: flex-end; }
    .filters .field { flex: 1 1 12rem; max-width: 22rem; }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .small { font-size: var(--font-size-xs); overflow-wrap: anywhere; }
    .nowrap { white-space: nowrap; }
    .purge { margin-top: var(--space-6); }
    .purge h2 { font-size: var(--font-size-lg); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-3); }
  `,
})
export class CastPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  readonly filtre = input<Filter | undefined, unknown>(undefined, {
    transform: (v: unknown) => (Object.keys(FILTER_LABELS).includes(v as string) ? (v as Filter) : undefined),
  });
  readonly q = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly filters: Filter[] = ['missing', 'failed', 'ok'];
  protected readonly filterLabels = FILTER_LABELS;
  protected readonly statusLabels = CAST_STATUS_LABELS;
  protected readonly num = formatNumber;
  protected readonly bytes = formatBytes;
  protected readonly busy = signal(false);
  protected readonly confirmPurge = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  private readonly reloads = signal(0);
  protected readonly summary = loadOn(this.reloads, () => this.api.castSummary());
  private readonly query = computed(() => ({
    filter: this.filtre(),
    q: this.q()?.trim(),
    page: this.page() - 1,
    size: PAGE_SIZE,
    n: this.reloads(),
  }));
  protected readonly list = loadOn(this.query, ({ n: _n, ...q }) => this.api.castList(q));

  time(iso: string): string {
    return formatDateTime(iso);
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

  refresh(e: CastAdminEntry): void {
    this.busy.set(true);
    this.api.refreshCast(e.animeId).subscribe({
      next: ({ running }) => {
        this.busy.set(false);
        this.message.set({
          text: running
            ? `« ${e.title} » : distribution remise en file (après les métadonnées).`
            : `« ${e.title} » : remise en file, mais la récupération est arrêtée (CAST_ENABLED=false).`,
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

  purge(): void {
    this.busy.set(true);
    this.api.purgeCast().subscribe({
      next: ({ purged }) => {
        this.busy.set(false);
        this.confirmPurge.set(false);
        this.message.set({ text: `Distribution effacée (${formatNumber(purged)} rôle${purged > 1 ? 's' : ''}, images comprises).`, error: false });
        this.reloads.update((n) => n + 1);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }
}
