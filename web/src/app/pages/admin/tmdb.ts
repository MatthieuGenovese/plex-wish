import { Component, computed, inject, input, numberAttribute, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatchStatus } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatDateTime, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { REASON_LABELS, STATUS_LABELS } from './metadata';
import { TmdbDialog } from './tmdb-dialog';

const PAGE_SIZE = 50;

/**
 * Synopsis en français (TMDB) : état, animés sans synopsis français d'abord, correction manuelle verrouillée,
 * purge (fin de licence TMDB). État dans l'URL : ?tous=1&statut=&q=&page=
 */
@Component({
  selector: 'app-admin-tmdb',
  imports: [Pager, RouterLink, TmdbDialog],
  template: `
    @let sum = summary();
    @if (sum.data; as s) {
      @if (!s.configured) {
        <div class="alert alert-warning" role="status">
          <p>TMDB n’est pas configuré : les synopsis restent en anglais (AniList). Pour les avoir en français, saisir une clé
            TMDB dans <a routerLink="/admin/settings">Administration › Réglages</a>.</p>
        </div>
      } @else {
        <p class="muted" role="status">
          {{ num(s.withFrenchSynopsis) }} animé{{ s.withFrenchSynopsis > 1 ? 's' : '' }} sur {{ num(s.total) }} avec un synopsis en français.
          @if (s.pausedUntil) {
            TMDB indisponible ({{ s.lastUnavailable }}) : reprise vers {{ time(s.pausedUntil) }}.
          } @else if (s.counts.PENDING > 0) {
            {{ num(s.counts.PENDING) }} en attente (après AniList).
          }
          @if (s.refreshDue > 0) { {{ num(s.refreshDue) }} fiche{{ s.refreshDue > 1 ? 's' : '' }} à rafraîchir (plus de 5 mois). }
        </p>
      }
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
        <label for="t-status">Statut</label>
        <select id="t-status" (change)="navigate({ statut: $any($event.target).value || null })">
          <option value="" [selected]="!statut()">Tous</option>
          @for (st of statuses; track st) { <option [value]="st" [selected]="statut() === st">{{ labels[st] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="t-q">Animé</label>
        <input id="t-q" type="search" [value]="q() ?? ''" autocomplete="off" (input)="onSearch($any($event.target).value)" />
      </div>
      <label class="check">
        <input type="checkbox" [checked]="!tous()" (change)="navigate({ tous: $any($event.target).checked ? null : '1' })" />
        Sans synopsis français seulement
      </label>
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
                <th scope="col">Dossier</th><th scope="col">Fiche TMDB</th><th scope="col">Statut</th>
                <th scope="col">Synopsis</th><th scope="col"><span class="visually-hidden">Action</span></th>
              </tr>
            </thead>
            <tbody>
              @for (e of page.items; track e.animeId) {
                <tr>
                  <th scope="row">{{ e.title }}</th>
                  <td>
                    @if (e.tmdbId) {
                      @if (e.frenchTitle) { <div>{{ e.frenchTitle }}</div> }
                      <div class="muted">{{ e.tmdbType === 'tv' ? 'série' : 'film' }} {{ e.tmdbId }}
                        @if (e.url) { · <a [href]="e.url" target="_blank" rel="noopener noreferrer">voir</a> }
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
                  <td class="nowrap">{{ e.hasFrenchSynopsis ? 'français' : 'anglais' }}</td>
                  <td><button type="button" class="btn-small" (click)="dialog().open(e)"
                              [attr.aria-label]="'Modifier la fiche TMDB de ' + e.title">Modifier</button></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages TMDB" (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }

    <section class="card purge" aria-labelledby="t-purge">
      <h2 id="t-purge">Effacer les données TMDB</h2>
      <p class="muted">En cas d’arrêt de l’utilisation de TMDB (fin de licence), toutes les données TMDB doivent être effacées,
        corrections manuelles comprises. Les synopsis repassent en anglais. Retirer aussi <code>TMDB_READ_TOKEN</code>
        de <code>.env</code>, sinon la récupération recommence.</p>
      @if (!confirmPurge()) {
        <button type="button" class="btn-danger" (click)="confirmPurge.set(true)">Effacer toutes les données TMDB…</button>
      } @else {
        <p role="alert"><strong>Tout effacer ?</strong> Cette action est définitive.</p>
        <div class="actions">
          <button type="button" (click)="confirmPurge.set(false)">Annuler</button>
          <button type="button" class="btn-danger" (click)="purge()" [disabled]="busy()">Confirmer l’effacement</button>
        </div>
      }
    </section>
    <app-tmdb-dialog (changed)="onChanged($event)" />
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
    .check { display: flex; align-items: center; gap: var(--space-2); min-height: var(--target-size); margin-bottom: var(--space-4); }
    .requeue { margin-bottom: var(--space-4); }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .reason { font-size: var(--font-size-xs); }
    .nowrap { white-space: nowrap; }
    .purge { margin-top: var(--space-6); }
    .purge h2 { font-size: var(--font-size-lg); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-3); }
  `,
})
export class TmdbPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  protected readonly dialog = viewChild.required(TmdbDialog);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  readonly statut = input<MatchStatus | undefined, unknown>(undefined, {
    transform: (v: unknown) => (Object.keys(STATUS_LABELS).includes(v as string) ? (v as MatchStatus) : undefined),
  });
  readonly q = input<string>();
  /** ?tous=1 : tous les animés ; par défaut, seulement ceux sans synopsis français. */
  readonly tous = input(false, { transform: (v: unknown) => v === '1' || v === true });
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly statuses: MatchStatus[] = ['UNMATCHED', 'DOUBTFUL', 'MATCHED', 'MANUAL', 'PENDING'];
  protected readonly labels = STATUS_LABELS;
  protected readonly reasons = REASON_LABELS;
  protected readonly num = formatNumber;
  protected readonly busy = signal(false);
  protected readonly confirmPurge = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  private readonly reloads = signal(0);
  protected readonly summary = loadOn(this.reloads, () => this.api.tmdbSummary());
  private readonly query = computed(() => ({
    status: this.statut(),
    q: this.q()?.trim(),
    noFrench: !this.tous(),
    page: this.page() - 1,
    size: PAGE_SIZE,
    n: this.reloads(),
  }));
  protected readonly list = loadOn(this.query, ({ n: _n, ...q }) => this.api.tmdb(q));

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
    this.api.requeueTmdb(status).subscribe({
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

  purge(): void {
    this.busy.set(true);
    this.api.purgeTmdb().subscribe({
      next: ({ purged }) => {
        this.busy.set(false);
        this.confirmPurge.set(false);
        this.message.set({ text: `Données TMDB effacées (${purged} animé${purged > 1 ? 's' : ''}).`, error: false });
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
