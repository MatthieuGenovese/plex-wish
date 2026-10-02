import { Component, ElementRef, computed, inject, input, numberAttribute, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NEVER } from 'rxjs';
import { CATEGORY_LABELS, ISSUE_CATEGORIES, Issue, IssueCategory } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { formatDateTime, formatDuration, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { OverrideDialog } from './override-dialog';
import { ScanStatusBadge } from './scan-status';

const PAGE_SIZE = 50;

const SOURCE_LABELS: Record<string, string> = {
  NAME_SXXEXX: 'nom (SxxExx)',
  NAME_NXEE: 'nom (NxEE)',
  NAME_S: 'nom (S + numéro)',
  NAME_SPECIAL: 'nom (spécial)',
  FOLDER: 'dossier',
  SPECIAL_FOLDER: 'dossier spécial',
  DEFAULT: 'défaut (saison 1)',
  OVERRIDE: 'correction',
};

/**
 * Rapport d'un scan : résumé par catégorie, puis liste filtrable et paginée des fichiers signalés
 * (id, chemin relatif à la racine de la bibliothèque), avec correction manuelle.
 * État dans l'URL : ?scan=&categorie=&anime=&page=
 */
@Component({
  selector: 'app-admin-report',
  imports: [RouterLink, Pager, OverrideDialog, ScanStatusBadge],
  template: `
    @let rep = report();
    @if (rep.status === 404) {
      <p class="alert">Aucun scan n’a encore été lancé. <a routerLink="/admin/scan">Lancer un scan</a></p>
    } @else if (rep.error) {
      <div class="alert alert-error" role="alert"><p>{{ rep.error }}</p></div>
    } @else if (rep.data; as r) {
      <div class="page-header">
        <h2>Rapport du scan n° {{ r.id }} <app-scan-status [status]="r.status" /></h2>
        <span class="muted">{{ date(r.startedAt) }} · {{ duration(r.stats?.durationMs) }}</span>
      </div>
      @if (r.status === 'RUNNING') {
        <p class="alert">Ce scan est en cours : le rapport sera complet à la fin. <a routerLink="/admin/scan">Suivre le scan</a></p>
      } @else if (r.status === 'FAILED') {
        <div class="alert alert-error"><p>Ce scan a échoué : {{ r.failureReason }}</p><p><a routerLink="/admin/scan">Page du scan</a></p></div>
      }

      @if (r.stats; as s) {
        <p class="muted">{{ num(s.videos) }} vidéos · {{ num(s.animeCount) }} animés · {{ num(s.newFiles) }} nouveaux fichiers ·
          {{ num(s.overridesApplied) }} correction{{ s.overridesApplied > 1 ? 's' : '' }} appliquée{{ s.overridesApplied > 1 ? 's' : '' }}
          @if (subtitles(); as n) { · {{ num(n) }} sous-titres externes (non associés) }
        </p>
        <ul class="summary">
          <li class="tile ok"><span class="value">{{ num(s.episodes) }}</span><span>Épisodes reconnus</span></li>
          <li class="tile"><span class="value">{{ num(s.extras) }}</span><span>Extras</span></li>
          @for (c of categories; track c) {
            <li>
              <a class="tile" [class.warn]="(r.issueCounts[c] ?? 0) > 0" [class.selected]="categorie() === c"
                 [routerLink]="[]" [queryParams]="{ categorie: c, page: null }" queryParamsHandling="merge">
                <span class="value">{{ num(r.issueCounts[c] ?? 0) }}</span><span>{{ labels[c] }}</span>
              </a>
            </li>
          }
        </ul>
      }

      <section aria-labelledby="issues-title">
        <h3 id="issues-title" tabindex="-1" #issuesTitle>Fichiers signalés</h3>
        <form class="filters" (submit)="$event.preventDefault()">
          <div class="field">
            <label for="f-category">Catégorie</label>
            <!-- [selected] par option : un [value] sur le <select> passerait avant la création des options du @for. -->
            <select id="f-category" (change)="filter({ categorie: $any($event.target).value || null })">
              <option value="" [selected]="!categorie()">Toutes</option>
              @for (c of categories; track c) { <option [value]="c" [selected]="categorie() === c">{{ labels[c] }}</option> }
            </select>
          </div>
          <div class="field">
            <label for="f-anime">Animé</label>
            <input id="f-anime" type="search" [value]="anime() ?? ''" autocomplete="off"
                   (input)="onAnime($any($event.target).value)" placeholder="Filtrer par titre" />
          </div>
        </form>

        <div aria-live="polite">
          @if (saved(); as text) { <div class="alert alert-success" role="status"><p>{{ text }} <a routerLink="/admin/scan">Relancer un scan</a></p></div> }
        </div>

        @let l = issues();
        @if (l.error) {
          <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
        } @else if (l.data; as page) {
          <p class="muted" role="status">{{ num(page.total) }} fichier{{ page.total > 1 ? 's' : '' }}</p>
          @if (page.items.length > 0) {
            <div class="table-wrap" [attr.aria-busy]="l.loading">
              <table class="wide">
                <thead>
                  <tr>
                    <th scope="col">Fichier</th><th scope="col">Catégorie</th><th scope="col">Animé</th>
                    <th scope="col">Chemin (relatif à la bibliothèque)</th><th scope="col">S / É</th>
                    <th scope="col">Détail</th><th scope="col"><span class="visually-hidden">Action</span></th>
                  </tr>
                </thead>
                <tbody>
                  @for (i of page.items; track i.id) {
                    <tr>
                      <td class="mono">{{ i.mediaFileId ?? '—' }}</td>
                      <td>{{ labels[i.category] }}</td>
                      <td>{{ i.animeTitle ?? '—' }}</td>
                      <td class="path">{{ i.relativePath }}</td>
                      <td class="nowrap">{{ se(i) }}</td>
                      <td class="detail">
                        @if (i.category === 'DUPLICATE') {
                          <div>Conservé : <span class="mono">{{ i.keptRelativePath }}</span></div>
                          <div class="muted">Saison d’après : {{ source(i.seasonSource) }} (écarté), {{ source(i.keptSeasonSource) }} (conservé)</div>
                        } @else if (i.category === 'SEASON_MISMATCH') {
                          <div>Saison d’après {{ source(i.seasonSource) }}</div>
                        }
                        @if (i.detail) { <div class="muted">{{ i.detail }}</div> }
                      </td>
                      <td>
                        @if (i.mediaFileId !== null && i.category !== 'MISSING') {
                          <button type="button" class="btn-small" (click)="dialog().open(i)"
                                  [attr.aria-label]="'Corriger le fichier ' + i.mediaFileId">Corriger</button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages des fichiers signalés"
                       (pageChange)="goToPage($event)" />
          }
        } @else {
          <p class="muted" role="status">Chargement…</p>
        }
      </section>
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
    <app-override-dialog (saved)="onSaved($event)" />
  `,
  styles: `
    .summary {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr)); gap: var(--space-2);
      margin: 0 0 var(--space-5); padding: 0; list-style: none;
    }
    .tile {
      display: flex; flex-direction: column; gap: var(--space-1); height: 100%;
      padding: var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); color: var(--color-text); text-decoration: none;
    }
    a.tile:hover { background: var(--color-surface-raised); color: var(--color-text); }
    .tile.selected { border-color: var(--color-accent); box-shadow: inset 0 0 0 1px var(--color-accent); }
    .value { font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; }
    .ok .value { color: var(--color-success); }
    .warn .value { color: var(--color-warning); }
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-3); }
    .filters .field { flex: 1 1 14rem; max-width: 24rem; }
    .nowrap { white-space: nowrap; }
    .detail { min-width: 14rem; word-break: break-word; }
  `,
})
export class ReportPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  protected readonly dialog = viewChild.required(OverrideDialog);
  private readonly issuesTitle = viewChild<ElementRef<HTMLElement>>('issuesTitle');
  private animeTimer: ReturnType<typeof setTimeout> | undefined;

  readonly scan = input<number | undefined, unknown>(undefined, { transform: optionalNumber });
  readonly categorie = input<IssueCategory | undefined, unknown>(undefined, {
    transform: (v: unknown) => (ISSUE_CATEGORIES as readonly unknown[]).includes(v) ? (v as IssueCategory) : undefined,
  });
  readonly anime = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  protected readonly categories = ISSUE_CATEGORIES;
  protected readonly labels = CATEGORY_LABELS;
  protected readonly date = formatDateTime;
  protected readonly duration = formatDuration;
  protected readonly num = formatNumber;
  protected readonly saved = signal<string | null>(null);

  protected readonly report = loadOn(this.scan, (id) => this.api.scanReport(id));
  private readonly query = computed(() => {
    const report = this.report().data;
    return report
      ? { scanId: report.id, category: this.categorie(), anime: this.anime()?.trim(), page: this.page() - 1, size: PAGE_SIZE }
      : null;
  });
  protected readonly issues = loadOn(this.query, (q) => (q ? this.api.issues(q) : NEVER));
  protected readonly subtitles = computed(() => this.report().data?.stats?.otherFiles?.['subtitle'] ?? 0);

  filter(params: Record<string, string | null>): void {
    this.router.navigate([], { queryParams: { ...params, page: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  onAnime(value: string): void {
    clearTimeout(this.animeTimer);
    this.animeTimer = setTimeout(() => this.filter({ anime: value.trim() || null }), 300);
  }

  goToPage(page: number): void {
    this.router.navigate([], { queryParams: { page: page === 0 ? null : page + 1 }, queryParamsHandling: 'merge' });
    this.issuesTitle()?.nativeElement.focus();
  }

  onSaved(issue: Issue): void {
    this.saved.set(`Correction enregistrée pour le fichier n° ${issue.mediaFileId} : elle sera appliquée au prochain scan.`);
  }

  se(i: Issue): string {
    if (i.seasonNumber === null && i.episodeNumber === null) {
      return '—';
    }
    return `S${i.seasonNumber ?? '?'} · É${i.episodeNumber ?? '?'}`;
  }

  source(code: string | null): string {
    return code ? SOURCE_LABELS[code] ?? code : '—';
  }
}

function optionalNumber(v: unknown): number | undefined {
  const n = numberAttribute(v, NaN);
  return Number.isInteger(n) && n > 0 ? n : undefined;
}
