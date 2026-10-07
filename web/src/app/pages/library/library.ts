import { Component, ElementRef, afterNextRender, computed, inject, input, numberAttribute, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { AnimeGrid } from '../../shared/anime-grid';
import { Pager } from '../../shared/pager';
import { loadOn } from '../../shared/load-state';

const PAGE_SIZE = 60;

/**
 * Bibliothèque : recherche, tri, pagination côté serveur (≈ 1 300 animés). L'état vit dans l'URL
 * (?q=&tri=&page=) : F5, retour arrière et lien partagé retrouvent la même vue.
 */
@Component({
  selector: 'app-library',
  imports: [AnimeGrid, Pager],
  template: `
    <div class="page-header">
      <h1>Bibliothèque</h1>
      @if (state().data; as d) {
        <p class="muted" role="status">
          {{ d.total }} animé{{ d.total > 1 ? 's' : '' }}{{ q() ? ' pour « ' + q() + ' »' : '' }}
        </p>
      }
    </div>

    <form class="filters" role="search" (submit)="$event.preventDefault()">
      <div class="field search">
        <label for="q">Rechercher un titre</label>
        <input id="q" type="search" [value]="q() ?? ''" (input)="onSearch($any($event.target).value)"
               placeholder="ex. frieren, chunibyo…" autocomplete="off" spellcheck="false" />
      </div>
      <div class="field">
        <label for="sort">Trier par</label>
        <select id="sort" [value]="sort()" (change)="navigate({ tri: $any($event.target).value === 'title' ? null : 'recent', page: null })">
          <option value="title">Titre (A → Z)</option>
          <option value="recent">Derniers ajouts</option>
        </select>
      </div>
    </form>

    <h2 class="visually-hidden" tabindex="-1" #results>Résultats</h2>
    @let s = state();
    @if (s.error) {
      <div class="alert alert-error" role="alert">
        <p>{{ s.error }}</p>
        <button type="button" (click)="state.reload()">Réessayer</button>
      </div>
    } @else if (s.data; as d) {
      @if (d.items.length === 0) {
        <p class="alert">Aucun animé ne correspond à « {{ q() }} ».</p>
      } @else {
        <div [class.loading]="s.loading" [attr.aria-busy]="s.loading">
          <app-anime-grid [animes]="d.items" />
        </div>
        <app-pager [page]="d.page" [total]="d.total" [size]="d.size" label="Pages de la bibliothèque"
                   (pageChange)="goToPage($event)" />
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: var(--space-3); }
    .filters .field { flex: 0 1 14rem; }
    .filters .search { flex: 1 1 18rem; }
    .loading { opacity: 0.6; }
  `,
})
export class LibraryPage {
  private readonly api = inject(LibraryApi);
  private readonly router = inject(Router);
  private readonly results = viewChild.required<ElementRef<HTMLElement>>('results');
  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  // Query params (withComponentInputBinding). Page en base 1 dans l'URL, pour les humains.
  readonly q = input<string>();
  readonly tri = input<string>();
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });
  /** Onglet « Rechercher » (téléphone) : le champ prend le focus, le clavier s'ouvre. */
  readonly searchMode = input(false);

  constructor() {
    afterNextRender(() => {
      if (this.searchMode()) document.getElementById('q')?.focus();
    });
  }

  protected readonly sort = computed<'recent' | 'title'>(() => (this.tri() === 'recent' ? 'recent' : 'title'));
  private readonly query = computed(() => ({
    q: this.q()?.trim() || undefined,
    sort: this.sort(),
    page: this.page() - 1,
    size: PAGE_SIZE,
  }));
  protected readonly state = loadOn(this.query, (query) => this.api.animes(query));

  onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.navigate({ q: value.trim() || null, page: null }, true), 300);
  }

  goToPage(page: number): void {
    this.navigate({ page: page === 0 ? null : page + 1 });
    this.results().nativeElement.focus(); // clavier : on repart du début des résultats
  }

  navigate(params: Record<string, string | number | null>, replaceUrl = false): void {
    this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge', replaceUrl });
  }
}
