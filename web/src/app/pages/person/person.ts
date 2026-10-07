import { Component, computed, effect, inject, input } from '@angular/core';
import { PersonRole } from '../../core/api-types';
import { RouterLink } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';
import { StateBox } from '../../shared/state';
import { ROLE_LABELS } from '../anime-detail/cast-section';
import { pageTitle } from '../../core/title-strategy';

/**
 * Page d'un comédien : photo, nom romanisé et japonais, et les animés de la bibliothèque où il joue
 * (affiche, nom du personnage joué, sans image de personnage). Jamais sa filmographie complète.
 */
@Component({
  selector: 'app-person',
  imports: [RouterLink, Poster, StateBox],
  template: `
    @let s = person();
    @if (s.error) {
      <app-state [icon]="s.status === 404 ? 'sentiment_dissatisfied' : 'cloud_off'" [error]="s.status !== 404"
                 [heading]="s.status === 404 ? 'Ce comédien ne joue dans aucun animé disponible de la bibliothèque' : 'Impossible de charger la page'"
                 [message]="s.status === 404 ? null : s.error">
        <a class="btn btn-ghost" routerLink="/anime">Retour à la bibliothèque</a>
      </app-state>
    } @else if (s.data; as p) {
      <header class="person-head">
        <app-poster class="person-photo" variant="round" [title]="p.name" [url]="p.imageUrl" [eager]="true" />
        <div class="person-text">
          <p class="person-kicker">Comédien de doublage</p>
          <h1>{{ p.name }}</h1>
          @if (p.nativeName) { <p class="native" lang="ja">{{ p.nativeName }}</p> }
          <p class="muted">{{ groups().length }} animé{{ groups().length > 1 ? 's' : '' }} de la bibliothèque
            · <span class="source">Source : <a [href]="p.sourceUrl" target="_blank" rel="noopener noreferrer">AniList</a></span></p>
        </div>
      </header>
      <h2>Dans la bibliothèque</h2>
      <ul class="card-grid person-grid">
        @for (g of groups(); track g.animeId) {
          <li class="anime-card">
            <a class="card-link" [routerLink]="['/anime', g.animeId]">
              <span class="card-art"><app-poster [title]="g.animeTitle" [url]="g.posterUrl" /></span>
              <span class="card-title">{{ g.animeTitle }}</span>
              @if (g.year) { <span class="card-meta num">{{ g.year }}</span> }
              @for (r of g.roles; track $index) {
                <span class="role"><span class="visually-hidden">Rôle : </span>{{ r.character.name }}
                  <span class="badge" [class.badge-success]="r.role === 'MAIN'">{{ roles[r.role] }}</span></span>
              }
            </a>
          </li>
        }
      </ul>
    } @else {
      <header class="person-head" aria-hidden="true"><span class="skeleton person-photo" style="aspect-ratio:1;border-radius:50%"></span>
        <div class="person-text"><span class="skeleton skeleton-line" style="width:12rem;height:2rem"></span></div></header>
      <p class="visually-hidden" role="status">Chargement…</p>
    }
  `,
})
export class PersonPage {
  private readonly api = inject(LibraryApi);
  /** Paramètre de route :id (identifiant AniList du comédien). */
  readonly id = input.required<string>();
  protected readonly roles = ROLE_LABELS;
  protected readonly person = loadOn(this.id, (id) => this.api.person(id));
  /** Un animé par carte, avec tous les personnages qu'il y joue. */
  protected readonly groups = computed(() => {
    const out: { animeId: number; animeTitle: string; year: number | null; posterUrl: string | null; roles: PersonRole[] }[] = [];
    for (const r of this.person().data?.roles ?? []) {
      const last = out[out.length - 1];
      if (last && last.animeId === r.animeId) last.roles.push(r);
      else out.push({ animeId: r.animeId, animeTitle: r.animeTitle, year: r.year, posterUrl: r.posterUrl, roles: [r] });
    }
    return out;
  });

  constructor() {
    effect(() => {
      const name = this.person().data?.name;
      if (name) document.title = pageTitle(name);
    });
  }
}
