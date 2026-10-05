import { Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';

export const ROLE_LABELS: Record<string, string> = { MAIN: 'Principal', SUPPORTING: 'Secondaire' };

/**
 * Distribution d'un animé : pour chaque rôle, la photo et le nom du doubleur japonais (lien vers sa page), le
 * personnage qu'il joue dans cet animé et le rôle (principal / secondaire). Pas d'image de personnage.
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
                @if (e.person; as p) {
                  <a class="card-link" [routerLink]="['/personne', p.id]"
                     [attr.aria-label]="p.name + ', voix de ' + e.character.name + ' (' + roles[e.role] + ')'">
                    <app-poster [title]="p.name" [url]="p.imageUrl" />
                    <span class="name">{{ p.name }}</span>
                    <span class="character" [attr.title]="e.character.nativeName">{{ e.character.name }}</span>
                    <span class="badge" [class.badge-success]="e.role === 'MAIN'">{{ roles[e.role] }}</span>
                  </a>
                } @else {
                  <div class="card-link">
                    <app-poster [title]="e.character.name" />
                    <span class="name muted">Voix non renseignée</span>
                    <span class="character" [attr.title]="e.character.nativeName">{{ e.character.name }}</span>
                    <span class="badge" [class.badge-success]="e.role === 'MAIN'">{{ roles[e.role] }}</span>
                  </div>
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
    .entry { min-width: 0; }
    .card-link {
      display: flex; flex-direction: column; gap: var(--space-1); height: 100%;
      padding: var(--space-1); border-radius: var(--radius); color: var(--color-text); text-decoration: none;
    }
    a.card-link:hover, a.card-link:focus-visible { background: var(--color-surface-raised); color: var(--color-text); }
    .name { font-weight: var(--font-weight-medium); line-height: var(--line-height-tight); overflow-wrap: anywhere; margin-top: var(--space-1); }
    .character { color: var(--color-text-muted); font-size: var(--font-size-sm); overflow-wrap: anywhere; }
    .badge { align-self: flex-start; }
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
