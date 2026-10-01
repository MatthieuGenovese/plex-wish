import { Component, input } from '@angular/core';
import { AnimeSummary } from '../core/api-types';
import { AnimeCard } from './anime-card';

/** Grille responsive de vignettes (liste sémantique : lecteurs d'écran et navigation clavier). */
@Component({
  selector: 'app-anime-grid',
  imports: [AnimeCard],
  template: `
    <ul class="grid">
      @for (a of animes(); track a.id) {
        <li><app-anime-card [anime]="a" /></li>
      }
    </ul>
  `,
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(var(--card-min-width), 1fr));
      gap: var(--space-3);
      margin: 0 0 var(--space-5);
      padding: 0;
      list-style: none;
    }
    @media (max-width: 40rem) {
      .grid { grid-template-columns: repeat(2, 1fr); gap: var(--space-2); }
    }
  `,
})
export class AnimeGrid {
  readonly animes = input.required<AnimeSummary[]>();
}
