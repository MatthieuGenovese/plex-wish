import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { LibraryApi } from '../../core/library-api';
import { AnimeGrid } from '../../shared/anime-grid';
import { loadOn } from '../../shared/load-state';

/** Accueil : derniers ajouts, puis début de la bibliothèque (« continuer à regarder » viendra avec la progression). */
@Component({
  selector: 'app-home',
  imports: [AnimeGrid, RouterLink],
  template: `
    <h1 class="visually-hidden">Accueil</h1>
    <section aria-labelledby="recent-title">
      <h2 id="recent-title">Récemment ajoutés</h2>
      @let r = recent();
      @if (r.error) {
        <div class="alert alert-error" role="alert"><p>{{ r.error }}</p></div>
      } @else if (r.data; as page) {
        @if (page.total === 0) {
          <div class="alert">
            <p>La bibliothèque est vide.</p>
            @if (auth.isAdmin()) {
              <p><a routerLink="/admin/scan">Lancer un scan de la bibliothèque</a></p>
            } @else {
              <p>L’administrateur n’a pas encore lancé de scan.</p>
            }
          </div>
        } @else {
          <app-anime-grid [animes]="page.items" />
        }
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
    </section>

    @let l = library();
    @if (l.data; as page) {
      @if (page.total > 0) {
        <section aria-labelledby="library-title">
          <div class="page-header">
            <h2 id="library-title">Bibliothèque</h2>
            <a routerLink="/anime">Voir les {{ page.total }} animés et rechercher</a>
          </div>
          <app-anime-grid [animes]="page.items" />
          <p><a class="btn" routerLink="/anime">Toute la bibliothèque</a></p>
        </section>
      }
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(LibraryApi);
  private readonly none = signal(null);

  protected readonly recent = loadOn(this.none, () => this.api.animes({ sort: 'recent', size: 12 }));
  protected readonly library = loadOn(this.none, () => this.api.animes({ sort: 'title', size: 24 }));
}
