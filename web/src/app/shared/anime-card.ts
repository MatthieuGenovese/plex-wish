import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnimeSummary } from '../core/api-types';
import { Poster } from './poster';

/** Vignette d'un animé : affiche (ou visuel de remplacement), titre, année, nombre d'épisodes. */
@Component({
  selector: 'app-anime-card',
  imports: [RouterLink, Poster],
  template: `
    <a class="card-link" [routerLink]="['/anime', anime().id]">
      <app-poster class="poster" [title]="anime().title" [url]="anime().posterUrl" />
      <span class="title">{{ anime().title }}</span>
      <span class="meta">
        @if (anime().year) { {{ anime().year }} · }{{ anime().episodeCount }} épisode{{ anime().episodeCount > 1 ? 's' : '' }}
      </span>
    </a>
  `,
  styles: `
    :host { display: block; }
    .card-link {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      height: 100%;
      padding: var(--space-2);
      border-radius: var(--radius);
      color: var(--color-text);
      text-decoration: none;
      transition: background var(--transition);
    }
    .card-link:hover, .card-link:focus-visible { background: var(--color-surface-raised); color: var(--color-text); }
    .poster { margin-bottom: var(--space-1); }
    .title {
      font-weight: var(--font-weight-medium);
      line-height: var(--line-height-tight);
      overflow-wrap: anywhere;
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .meta { color: var(--color-text-muted); font-size: var(--font-size-sm); }
  `,
})
export class AnimeCard {
  readonly anime = input.required<AnimeSummary>();
}
