import { Component, ElementRef, afterNextRender, computed, inject, input, numberAttribute, signal, untracked, viewChild } from '@angular/core';
import { toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, of, switchMap, tap } from 'rxjs';
import { AnimeSummary, GenreCount } from '../../core/api-types';
import { errorMessage } from '../../core/errors';
import { AnimeQuery, AnimeSort, LibraryApi, WatchFilter } from '../../core/library-api';
import { AnimeGrid } from '../../shared/anime-grid';
import { Icon } from '../../shared/icon';
import { SearchBox } from '../../shared/search-box';
import { StateBox } from '../../shared/state';

const PAGE_SIZE = 60;

/** Périodes proposées (valeur dans l'URL → bornes d'années). */
export const PERIODS: { key: string; label: string; from?: number; to?: number }[] = [
  { key: '2020', label: 'Années 2020', from: 2020, to: 2029 },
  { key: '2010', label: 'Années 2010', from: 2010, to: 2019 },
  { key: '2000', label: 'Années 2000', from: 2000, to: 2009 },
  { key: '1990', label: 'Années 1990', from: 1990, to: 1999 },
  { key: 'avant-1990', label: 'Avant 1990', to: 1989 },
];
const WATCH: Record<string, { api: WatchFilter; label: string }> = {
  'non-vus': { api: 'unseen', label: 'Non vus' },
  'en-cours': { api: 'inProgress', label: 'En cours' },
  vus: { api: 'seen', label: 'Vus' },
};
const SORTS: Record<string, { api: AnimeSort; label: string }> = {
  titre: { api: 'title', label: 'Titre (A → Z)' },
  recent: { api: 'recent', label: 'Derniers ajouts' },
  annee: { api: 'year', label: 'Année (récents d’abord)' },
};

interface Results {
  loading: boolean;
  error: string | null;
  total: number;
  items: AnimeSummary[];
}

/**
 * Bibliothèque et recherche (≈ 1 300 animés) : recherche au fil de la frappe, filtres (vus / en cours / non vus,
 * lisible dans un navigateur, genre, période), tri (titre, derniers ajouts, année), « Charger plus ». Tout l'état
 * est dans l'URL (?q=&tri=&vu=&navigateur=&genre=&periode=&pages=) : F5, retour arrière et lien partagé
 * retrouvent la même vue. Onglet « Rechercher » (téléphone) : même page, champ focalisé.
 */
@Component({
  selector: 'app-library',
  imports: [AnimeGrid, Icon, SearchBox, StateBox],
  template: `
    <div class="library">
      <div class="library-head">
        <h1>{{ searchMode() ? 'Rechercher' : 'Bibliothèque' }}</h1>
        <p class="muted library-count" role="status" aria-live="polite">{{ countLabel() }}</p>
      </div>

      <app-search-box #search class="library-search" [id]="'q'" [value]="q()" [navigates]="false" (typed)="onSearch($event)" />

      <div class="filters" role="group" aria-label="Filtres">
        @for (w of watchOptions; track w.key) {
          <button type="button" class="chip" [attr.aria-pressed]="vu() === w.key" (click)="toggleWatch(w.key)">
            @if (vu() === w.key) { <app-icon name="check" size="sm" /> }{{ w.label }}
          </button>
        }
        <button type="button" class="chip" [attr.aria-pressed]="navigateur() === '1'" (click)="navigate({ navigateur: navigateur() === '1' ? null : '1' })">
          @if (navigateur() === '1') { <app-icon name="check" size="sm" /> }Lisible dans le navigateur
        </button>
        <label class="visually-hidden" for="f-genre">Genre</label>
        <select id="f-genre" class="select-chip" [class.selected]="!!genre()" (change)="navigate({ genre: value($event) || null })">
          <option value="" [selected]="!genre()">Tous les genres</option>
          @if (genre() && !genreKnown()) {
            <option [value]="genre()" selected>{{ genre() }}</option>
          }
          @for (g of genres(); track g.genre) {
            <option [value]="g.genre" [selected]="genre() === g.genre">{{ g.label }} ({{ g.animeCount }})</option>
          }
        </select>
        <label class="visually-hidden" for="f-period">Période</label>
        <select id="f-period" class="select-chip" [class.selected]="!!periode()" (change)="navigate({ periode: value($event) || null })">
          <option value="" [selected]="!periode()">Toutes les années</option>
          @for (p of periods; track p.key) {
            <option [value]="p.key" [selected]="periode() === p.key">{{ p.label }}</option>
          }
        </select>
        <label class="visually-hidden" for="f-sort">Trier par</label>
        <select id="f-sort" class="select-chip" (change)="navigate({ tri: value($event) === 'titre' ? null : value($event) })">
          @for (s of sortOptions; track s.key) {
            <option [value]="s.key" [selected]="sortKey() === s.key">{{ s.label }}</option>
          }
        </select>
        @if (filtered()) {
          <button type="button" class="btn-link clear-filters" (click)="clearFilters()">Effacer les filtres</button>
        }
      </div>

      <h2 class="visually-hidden" tabindex="-1" #resultsTitle>Résultats</h2>
      @let r = results();
      @if (r.error) {
        <app-state icon="cloud_off" heading="Impossible de charger la bibliothèque" [message]="r.error" [error]="true">
          <button type="button" class="btn-primary" (click)="reload()"><app-icon name="refresh" />Réessayer</button>
        </app-state>
      } @else if (r.items.length > 0) {
        <app-anime-grid [animes]="r.items" />
        @if (r.items.length < r.total) {
          <div class="load-more">
            <button type="button" class="btn-ghost" [disabled]="r.loading" (click)="loadMore()">
              {{ r.loading ? 'Chargement…' : 'Afficher plus' }}</button>
            <span class="muted num">{{ r.items.length }} sur {{ r.total }}</span>
          </div>
        }
      } @else if (r.loading) {
        <ul class="card-grid" aria-hidden="true">
          @for (i of skeletons; track i) {
            <li><span class="skeleton" style="display:block;aspect-ratio:2/3"></span><span class="skeleton skeleton-line" style="width:80%"></span></li>
          }
        </ul>
      } @else {
        @let e = empty();
        <app-state [icon]="e.icon" [heading]="e.heading" [message]="e.message">
          @if (filtered() || q()) {
            <button type="button" class="btn-ghost" (click)="clearAll()">Tout effacer</button>
          }
        </app-state>
      }
    </div>
  `,
})
export class LibraryPage {
  private readonly api = inject(LibraryApi);
  private readonly router = inject(Router);
  private readonly resultsHeading = viewChild.required<ElementRef<HTMLElement>>("resultsTitle");
  private readonly searchBox = viewChild.required(SearchBox);
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  // Paramètres de l'URL (withComponentInputBinding).
  readonly q = input<string>();
  readonly tri = input<string>();
  readonly vu = input<string>();
  readonly navigateur = input<string>();
  readonly genre = input<string>();
  readonly periode = input<string>();
  readonly pages = input(1, { transform: (v: unknown) => Math.min(20, Math.max(1, numberAttribute(v, 1))) });
  /** Onglet « Rechercher » (téléphone) : le champ prend le focus, le clavier s'ouvre. */
  readonly searchMode = input(false);

  protected readonly periods = PERIODS;
  protected readonly watchOptions = Object.entries(WATCH).map(([key, v]) => ({ key, label: v.label }));
  protected readonly sortOptions = Object.entries(SORTS).map(([key, v]) => ({ key, label: v.label }));
  protected readonly skeletons = Array.from({ length: 12 }, (_, i) => i);
  protected readonly genres = signal<GenreCount[]>([]);
  protected readonly results = signal<Results>({ loading: true, error: null, total: 0, items: [] });
  private readonly reloads = signal(0);

  /** Genre de l'URL absent de la liste (aucun animé visible) : on l'affiche quand même, sélectionné. */
  protected readonly genreKnown = computed(() => this.genres().some((g) => g.genre === this.genre()));
  protected readonly sortKey = computed(() => (this.tri() && SORTS[this.tri()!] ? this.tri()! : 'titre'));
  protected readonly filtered = computed(() => !!(this.vu() || this.navigateur() || this.genre() || this.periode()));

  /** Requête API (sans la page) : la changer recharge depuis la première page. */
  private readonly query = computed<AnimeQuery>(() => {
    this.reloads();
    const period = PERIODS.find((p) => p.key === this.periode());
    return {
      q: this.q()?.trim() || undefined,
      sort: SORTS[this.sortKey()].api,
      watch: this.vu() ? WATCH[this.vu()!]?.api : undefined,
      browser: this.navigateur() === '1' ? true : undefined,
      genre: this.genre() || undefined,
      yearFrom: period?.from,
      yearTo: period?.to,
      size: PAGE_SIZE,
    };
  });

  /** Liste vide : le message dit pourquoi (seul filtre « En cours » ou « Vus » : rien de commencé encore). */
  protected readonly empty = computed(() => {
    const onlyWatch = !this.q() && !this.navigateur() && !this.genre() && !this.periode();
    if (onlyWatch && !this.vu()) {
      return { icon: 'video_library' as const, heading: 'La bibliothèque est vide',
        message: 'Aucun animé n’a encore été importé. Revenez un peu plus tard.' };
    }
    if (onlyWatch && this.vu() === 'en-cours') {
      return { icon: 'history' as const, heading: 'Aucun animé en cours',
        message: 'Les animés commencés dans l’application Android apparaîtront ici.' };
    }
    if (onlyWatch && this.vu() === 'vus') {
      return { icon: 'check_circle_fill' as const, heading: 'Aucun animé vu en entier pour l’instant',
        message: 'Un animé apparaît ici quand tous ses épisodes ont été regardés.' };
    }
    return {
      icon: 'search' as const,
      heading: this.q() ? `Aucun animé pour « ${this.q()} »` : 'Aucun animé ne correspond',
      message: this.filtered() ? 'Essayez avec moins de filtres, ou une partie du titre seulement.'
        : 'Vérifiez l’orthographe, ou cherchez une partie du titre.',
    };
  });

  protected readonly countLabel = computed(() => {
    const r = this.results();
    if (r.loading && r.items.length === 0) return 'Chargement…';
    if (r.error) return '';
    const n = `${r.total.toLocaleString('fr-FR')} animé${r.total > 1 ? 's' : ''}`;
    return this.q() ? `${n} pour « ${this.q()} »` : n;
  });

  constructor() {
    // Première page(s) à chaque changement de requête ; « pages » (F5 après « Afficher plus ») lu une seule fois.
    toObservable(this.query)
      .pipe(
        tap(() => this.results.update((r) => ({ ...r, loading: true, error: null }))),
        switchMap((query) => {
          const count = untracked(this.pages);
          return forkJoin(Array.from({ length: count }, (_, page) => this.api.animes({ ...query, page }))).pipe(
            map((pages) => ({ loading: false, error: null, total: pages[0].total, items: pages.flatMap((p) => p.items) })),
            catchError((err: unknown) => of({ loading: false, error: errorMessage(err), total: 0, items: [] })),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((r) => this.results.set(r));
    this.api.genres().pipe(catchError(() => of([])), takeUntilDestroyed()).subscribe((g) => this.genres.set(g));
    afterNextRender(() => {
      if (this.searchMode()) this.searchBox().focus();
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLSelectElement).value;
  }

  onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.navigate({ q: value.trim() || null }, true), 300);
  }

  toggleWatch(key: string): void {
    this.navigate({ vu: this.vu() === key ? null : key });
  }

  clearFilters(): void {
    this.navigate({ vu: null, navigateur: null, genre: null, periode: null });
  }

  clearAll(): void {
    this.navigate({ q: null, vu: null, navigateur: null, genre: null, periode: null });
  }

  reload(): void {
    this.reloads.update((n) => n + 1);
  }

  loadMore(): void {
    const r = this.results();
    const page = Math.ceil(r.items.length / PAGE_SIZE);
    this.results.set({ ...r, loading: true });
    this.api.animes({ ...this.query(), page }).subscribe({
      next: (p) => {
        this.results.set({ loading: false, error: null, total: p.total, items: [...r.items, ...p.items] });
        // Nombre de pages dans l'URL (F5 retrouve la même liste), sans recharger.
        this.router.navigate([], { queryParams: { pages: page + 1 }, queryParamsHandling: 'merge', replaceUrl: true });
      },
      error: (err: unknown) => this.results.set({ ...r, loading: false, error: errorMessage(err) }),
    });
  }

  /** Change l'URL ; tout changement de filtre repart de la première page. */
  navigate(params: Record<string, string | null>, replaceUrl = false): void {
    this.router.navigate([], { queryParams: { ...params, pages: null }, queryParamsHandling: 'merge', replaceUrl });
    if (!replaceUrl) this.resultsHeading().nativeElement.focus({ preventScroll: true });
  }
}
