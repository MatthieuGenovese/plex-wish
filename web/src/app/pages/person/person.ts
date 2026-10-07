import { Component, computed, effect, inject, input } from '@angular/core';
import { PersonRole } from '../../core/api-types';
import { RouterLink } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';
import { ROLE_LABELS } from '../anime-detail/cast-section';
import { pageTitle } from '../../core/title-strategy';

/**
 * Page d'un comédien : photo, nom romanisé et japonais, et les animés de la bibliothèque où il joue
 * (affiche, nom du personnage joué, sans image de personnage). Jamais sa filmographie complète.
 */
@Component({
  selector: 'app-person',
  imports: [RouterLink, Poster],
  template: `
    @let s = person();
    @if (s.error) {
      <div class="alert alert-error" role="alert">
        <p>{{ s.status === 404 ? 'Ce comédien ne joue dans aucun animé disponible de la bibliothèque.' : s.error }}</p>
        <p><a routerLink="/anime">Retour à la bibliothèque</a></p>
      </div>
    } @else if (s.data; as p) {
      <div class="hero">
        <app-poster class="photo" [title]="p.name" [url]="p.imageUrl" [eager]="true" />
        <div>
          <h1>{{ p.name }}</h1>
          @if (p.nativeName) { <p class="native" lang="ja">{{ p.nativeName }}</p> }
          <p class="muted">Comédien de doublage · {{ groups().length }} animé{{ groups().length > 1 ? 's' : '' }} de la bibliothèque</p>
          <p class="source">Source : <a [href]="p.sourceUrl" target="_blank" rel="noopener noreferrer">AniList</a></p>
        </div>
      </div>
      <h2>Dans la bibliothèque</h2>
      <ul class="grid">
        @for (g of groups(); track g.animeId) {
          <li>
            <a class="card-link" [routerLink]="['/anime', g.animeId]">
              <app-poster [title]="g.animeTitle" [url]="g.posterUrl" />
              <span class="title">{{ g.animeTitle }}@if (g.year) { <span class="muted"> · {{ g.year }}</span> }</span>
              @for (r of g.roles; track $index) {
                <span class="role">
                  <span><span class="visually-hidden">Rôle : </span>{{ r.character.name }}</span>
                  <span class="muted">{{ roles[r.role] }}</span>
                </span>
              }
            </a>
          </li>
        }
      </ul>
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .hero { display: grid; grid-template-columns: 11rem 1fr; gap: var(--space-5); align-items: start; margin-bottom: var(--space-6); }
    .hero h1 { margin-bottom: var(--space-1); }
    .native { font-size: var(--font-size-lg); margin-bottom: var(--space-3); }
    .source { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(var(--card-min-width), 1fr)); gap: var(--space-3); margin: 0; padding: 0; list-style: none; }
    .card-link { display: flex; flex-direction: column; gap: var(--space-1); padding: var(--space-2); border-radius: var(--radius);
      color: var(--color-text); text-decoration: none; height: 100%; }
    .card-link:hover, .card-link:focus-visible { background: var(--color-surface-raised); color: var(--color-text); }
    .title { font-weight: var(--font-weight-medium); line-height: var(--line-height-tight); overflow-wrap: anywhere; }
    .role { display: flex; flex-wrap: wrap; column-gap: var(--space-2); font-size: var(--font-size-sm); overflow-wrap: anywhere; }
    @media (max-width: 40rem) {
      .hero { grid-template-columns: 7rem 1fr; gap: var(--space-3); }
      .hero h1 { font-size: var(--font-size-xl); }
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
