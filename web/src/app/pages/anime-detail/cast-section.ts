import { Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';

export const ROLE_LABELS: Record<string, string> = { MAIN: 'Principal', SUPPORTING: 'Secondaire' };

/**
 * Distribution d'un animé : personnage (image, nom, rôle) et doubleur japonais (lien vers sa page).
 * Grille qui passe à la ligne selon la largeur (pas de défilement horizontal) ; rien n'est affiché s'il n'y a pas de distribution ou en cas d'erreur
 * (la fiche reste utilisable).
 */
@Component({
  selector: 'app-cast-section',
  imports: [RouterLink, Poster],
  template: `
    @if (cast().data; as c) {
      @if (c.items.length > 0) {
        <section aria-labelledby="cast-title" class="cast">
          <h2 id="cast-title">Distribution <span class="muted lang">· voix japonaises</span></h2>
          <ul class="strip">
            @for (e of c.items; track $index) {
              <li class="entry">
                <app-poster class="pic" [title]="e.character.name" [url]="e.character.imageUrl" />
                <span class="name" [attr.title]="e.character.nativeName">{{ e.character.name }}</span>
                <span class="badge" [class.badge-success]="e.role === 'MAIN'">{{ roles[e.role] }}</span>
                @if (e.person; as p) {
                  <a class="person" [routerLink]="['/personne', p.id]" [attr.aria-label]="p.name + ', voix de ' + e.character.name">
                    <app-poster class="face" [title]="p.name" [url]="p.imageUrl" />
                    <span>{{ p.name }}</span>
                  </a>
                } @else {
                  <span class="muted person-none">Voix non renseignée</span>
                }
              </li>
            }
          </ul>
          @if (c.source) {
            <p class="source">Distribution :
              @if (c.sourceUrl) { <a [href]="c.sourceUrl" target="_blank" rel="noopener noreferrer">{{ c.source }}</a> } @else { {{ c.source }} }
            </p>
          }
        </section>
      }
    }
  `,
  styles: `
    .cast { margin-top: var(--space-6); }
    .lang { font-size: var(--font-size-md); font-weight: var(--font-weight-normal); }
    .strip {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr)); gap: var(--space-4) var(--space-3);
      margin: 0 0 var(--space-3); padding: 0; list-style: none;
    }
    .entry { display: flex; flex-direction: column; gap: var(--space-1); min-width: 0; }
    .name { font-weight: var(--font-weight-medium); line-height: var(--line-height-tight); overflow-wrap: anywhere; }
    .badge { align-self: flex-start; }
    .person {
      display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-1);
      min-height: var(--target-size); padding: var(--space-1); border-radius: var(--radius);
      color: var(--color-text); text-decoration: none; font-size: var(--font-size-sm);
    }
    .person:hover, .person:focus-visible { background: var(--color-surface-raised); color: var(--color-text); }
    .face { width: 2.25rem; flex: 0 0 auto; }
    .person-none { font-size: var(--font-size-sm); margin-top: var(--space-1); }
    .source { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    @media (max-width: 40rem) { .strip { grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr)); } }
  `,
})
export class CastSection {
  private readonly api = inject(LibraryApi);
  readonly animeId = input.required<string>();
  protected readonly roles = ROLE_LABELS;
  protected readonly cast = loadOn(this.animeId, (id) => this.api.cast(id));
}
