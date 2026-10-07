import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnimeSummary } from '../core/api-types';
import { Poster } from './poster';
import { isNew } from './viewing';

/** Carte d'un animé : affiche (ou couverture composée), titre sur deux lignes, année et épisodes, badge « Nouveau ». */
@Component({
  selector: 'app-anime-card',
  imports: [RouterLink, Poster],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="card-link" [routerLink]="['/anime', anime().id]">
      <span class="card-art">
        <app-poster [title]="anime().title" [url]="anime().posterUrl" />
        @if (showNew() && fresh()) {
          <span class="badge badge-accent card-badge">Nouveau</span>
        }
      </span>
      <span class="card-title">{{ anime().title }}</span>
      <span class="card-meta num">{{ meta() }}</span>
    </a>
  `,
  host: { class: 'anime-card' },
})
export class AnimeCard {
  readonly anime = input.required<AnimeSummary>();
  /** Badge « Nouveau » (inutile dans la rangée « Récemment ajoutés »). */
  readonly showNew = input(true);
  protected readonly fresh = computed(() => isNew(this.anime().lastAddedAt));
  protected readonly meta = computed(() => {
    const a = this.anime();
    const eps = `${a.episodeCount} épisode${a.episodeCount > 1 ? 's' : ''}`;
    return a.year ? `${a.year} · ${eps}` : eps;
  });
}
