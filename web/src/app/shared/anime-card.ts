import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnimeSummary } from '../core/api-types';

/** Vignette d'un animé. Sans affiche (métadonnées plus tard) : couleur tirée du titre + initiales. */
@Component({
  selector: 'app-anime-card',
  imports: [RouterLink],
  template: `
    <a class="card-link" [routerLink]="['/anime', anime().id]">
      @if (anime().posterUrl; as url) {
        <img class="poster" [src]="url" alt="" loading="lazy" />
      } @else {
        <span class="poster placeholder" [style.--hue]="hue()" aria-hidden="true">{{ initials() }}</span>
      }
      <span class="title">{{ anime().title }}</span>
      <span class="meta">{{ anime().episodeCount }} épisode{{ anime().episodeCount > 1 ? 's' : '' }}</span>
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
    .poster {
      display: block;
      width: 100%;
      aspect-ratio: 2 / 3;
      border-radius: var(--radius);
      object-fit: cover;
      margin-bottom: var(--space-1);
    }
    .placeholder {
      display: grid;
      place-items: center;
      background: hsl(var(--hue) var(--poster-saturation) var(--poster-lightness));
      color: var(--color-text);
      font-size: var(--font-size-xxl);
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.05em;
    }
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

  protected readonly initials = computed(() =>
    this.anime()
      .title.split(/[\s_\-:]+/)
      .filter((w) => /^[\p{L}\p{N}]/u.test(w))
      .slice(0, 2)
      .map((w) => w[0].toUpperCase())
      .join(''),
  );

  protected readonly hue = computed(() => {
    let h = 0;
    for (const ch of this.anime().title) {
      h = (h * 31 + ch.charCodeAt(0)) % 360;
    }
    return h;
  });
}
