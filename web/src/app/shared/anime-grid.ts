import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AnimeSummary } from '../core/api-types';
import { AnimeCard } from './anime-card';

/** Grille responsive de cartes (liste sémantique : lecteurs d'écran et navigation clavier). */
@Component({
  selector: 'app-anime-grid',
  imports: [AnimeCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="card-grid">
      @for (a of animes(); track a.id) {
        <li><app-anime-card [anime]="a" /></li>
      }
    </ul>
  `,
})
export class AnimeGrid {
  readonly animes = input.required<AnimeSummary[]>();
}
