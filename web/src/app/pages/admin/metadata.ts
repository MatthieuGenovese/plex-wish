import { Component, computed, effect, inject, input, numberAttribute, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatchStatus, MetadataEntry } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { Poster } from '../../shared/poster';
import { MetadataDialog } from './metadata-dialog';

const PAGE_SIZE = 50;

export const STATUS_LABELS: Record<MatchStatus, string> = {
  MATCHED: 'Appariés',
  DOUBTFUL: 'Douteux',
  UNMATCHED: 'Non appariés',
  MANUAL: 'Corrigés à la main',
  PENDING: 'En attente',
};

export const REASON_LABELS: Record<string, string> = {
  NO_RESULT: 'aucun résultat',
  LOW_SCORE: 'titres trop différents',
  AMBIGUOUS: 'plusieurs fiches au même titre',
  CLOSE_CANDIDATE: 'un autre candidat très proche',
  BELOW_CONFIDENT: 'titre seulement proche',
  ERROR: 'erreurs répétées',
};

/**
 * Appariements AniList : état de la tâche, non appariés et douteux d'abord, correction manuelle verrouillée.
 * État dans l'URL : ?statut=&q=&page=
 */
@Component({
  selector: 'app-admin-metadata',
  imports: [Pager, Poster, MetadataDialog, RouterLink],
  template: `
    @let sum = summary();
    @if (sum.data; as s) {
      <p class="muted" role="status">
        @if (!s.enabled) {
          Récupération automatique désactivée (<code>METADATA_ENABLED=false</code>).
        } @else if (s.pausedUntil) {
          {{ s.provider }} indisponible ou limite de débit atteinte ({{ s.lastUnavailable }}) : reprise vers {{ time(s.pausedUntil) }}.
        } @else if (s.counts.PENDING > 0) {
          Récupération en cours depuis {{ s.provider }} : {{ num(s.counts.PENDING) }} animé{{ s.counts.PENDING > 1 ? 's' : '' }} restant{{ s.counts.PENDING > 1 ? 's' : '' }}, environ {{ s.estimatedMinutesLeft }} min.
        } @else {
          Tous les animés ont été traités ({{ s.provider }}).
        }
      </p>
      <ul class="summary">
        @for (st of statuses; track st) {
          <li>
            <a class="tile" [class.selected]="statut() === st" [class.warn]="(st === 'UNMATCHED' || st === 'DOUBTFUL') && s.counts[st] > 0"
               [routerLink]="[]" [queryParams]="{ statut: st, page: null }" queryParamsHandling="merge">
              <span class="value">{{ num(s.counts[st]) }}</span><span>{{ labels[st] }}</span>
            </a>
          </li>
        }
      </ul>
    }

    <form class="filters" (submit)="$event.preventDefault()">
      <div class="field">
        <label for="m-status">Statut</label>
        <select id="m-status" (change)="navigate({ statut: $any($event.target).value || null })">
          <option value="" [selected]="!statut()">Tous</option>
          @for (st of statuses; track st) { <option [value]="st" [selected]="statut() === st">{{ labels[st] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="m-q">Animé</label>
        <input id="m-q" type="search" [value]="q() ?? ''" autocomplete="off" (input)="onSearch($any($event.target).value)" />
      </div>
      @if (statut() === 'UNMATCHED' || statut() === 'DOUBTFUL') {
        <button type="button" class="requeue" (click)="requeue(statut()!)" [disabled]="busy()">Relancer l’appariement automatique</button>
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
      <p class="muted">{{ num(page.total) }} animé{{ page.total > 1 ? 's' : '' }}</p>
      @if (page.items.length > 0) {
        <div class="table-wrap">
          <table class="wide">
            <thead>
              <tr>
                <th scope="col"><span class="visually-hidden">Affiche</span></th><th scope="col">Dossier</th>
                <th scope="col">Fiche</th><th scope="col">Statut</th><th scope="col">Score</th>
                <th scope="col"><span class="visually-hidden">Action</span></th>
              </tr>
            </thead>
            <tbody>
              @for (e of page.items; track e.animeId) {
                <tr>
                  <td class="thumb"><app-poster [title]="e.title" [url]="e.posterUrl" /></td>
                  <th scope="row">{{ e.title }}</th>
                  <td>
                    @if (e.providerId) {
                      <div>{{ e.matchedTitle }}</div>
                      <div class="muted">{{ e.year ?? '' }} · AniList {{ e.providerId }}
                        @if (e.metadataUrl) { · <a [href]="e.metadataUrl" target="_blank" rel="noopener noreferrer">voir</a> }
                      </div>
                    } @else if (e.status === 'MANUAL') {
                      <span class="muted">Aucune fiche (choix de l’admin)</span>
                    } @else {
                      <span class="muted">—</span>
                    }
                  </td>
                  <td>
                    <span class="badge" [class.badge-success]="e.status === 'MATCHED' || e.status === 'MANUAL'"
                          [class.badge-warning]="e.status === 'DOUBTFUL' || e.status === 'PENDING'"
                          [class.badge-danger]="e.status === 'UNMATCHED'">{{ labels[e.status] }}</span>
                    @if (e.locked) { <span class="badge">verrouillé</span> }
                    @if (e.reason) { <div class="muted reason">{{ reasons[e.reason] ?? e.reason }}</div> }
                  </td>
                  <td class="nowrap">{{ e.score === null ? '—' : (e.score * 100).toFixed(0) + ' %' }}</td>
                  <td><button type="button" class="btn-small" (click)="dialog().open(e)"
                              [attr.aria-label]="'Modifier la fiche de ' + e.title">Modifier</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages des appariements" (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
    <app-metadata-dialog (changed)="onChanged($event)" />
  `,
  styles: `
    .summary { display: grid; grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr)); gap: var(--space-2); margin: 0 0 var(--space-4); padding: 0; list-style: none; }
    .tile { display: flex; flex-direction: column; padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); color: var(--color-text); text-decoration: none; height: 100%; }
    .tile:hover { background: var(--color-surface-raised); color: var(--color-text); }
    .tile.selected { border-color: var(--color-accent); box-shadow: inset 0 0 0 1px var(--color-accent); }
    .value { font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; }
    .warn .value { color: var(--color-warning); }
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: flex-end; }
    .filters .field { flex: 1 1 12rem; max-width: 22rem; }
    .requeue { margin-bottom: var(--space-4); }
    .thumb { width: 3.5rem; }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .reason { font-size: var(--font-size-xs); }
    .nowrap { white-space: nowrap; }
  `,
})
export class MetadataPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  protected readonly dialog = viewChild.required(MetadataDialog);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  readonly statut = input<MatchStatus | undefined, unknown>(undefined, {
    transform: (v: unknown) => (Object.keys(STATUS_LABELS).includes(v as string) ? (v as MatchStatus) : undefined),
  });
  readonly q = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly statuses: MatchStatus[] = ['UNMATCHED', 'DOUBTFUL', 'MATCHED', 'MANUAL', 'PENDING'];
  protected readonly labels = STATUS_LABELS;
  protected readonly reasons = REASON_LABELS;
  protected readonly num = formatNumber;
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  private readonly reloads = signal(0);
  protected readonly summary = loadOn(this.reloads, () => this.api.metadataSummary());
  private readonly query = computed(() => ({
    status: this.statut(),
    q: this.q()?.trim(),
    page: this.page() - 1,
    size: PAGE_SIZE,
    n: this.reloads(),
  }));
  protected readonly list = loadOn(this.query, ({ n: _n, ...q }) => this.api.metadata(q));

  constructor() {
    // Pendant la récupération (environ une heure au premier lancement), le résumé se met à jour tout seul.
    effect((onCleanup) => {
      const s = this.summary().data;
      if (s && s.enabled && s.counts.PENDING > 0) {
        const t = setTimeout(() => this.summary.reload(), 15_000);
        onCleanup(() => clearTimeout(t));
      }
    });
  }

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

  requeue(status: string): void {
    this.busy.set(true);
    this.api.requeueMetadata(status).subscribe({
      next: ({ requeued }) => {
        this.busy.set(false);
        this.message.set({ text: `${requeued} animé${requeued > 1 ? 's' : ''} remis en file : l’appariement automatique reprend.`, error: false });
        this.reloads.update((n) => n + 1);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }

  onChanged(text: string): void {
    this.message.set({ text, error: false });
    this.reloads.update((n) => n + 1);
  }
}

