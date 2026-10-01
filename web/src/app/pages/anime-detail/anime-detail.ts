import { Component, computed, effect, inject, input, linkedSignal, numberAttribute } from '@angular/core';
import { RouterLink } from '@angular/router';
import { of } from 'rxjs';
import { EpisodeSummary, Season } from '../../core/api-types';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';

/** Au-delà, les épisodes d'une saison sont présentés par tranches (One Piece : 1 000+ épisodes). */
export const CHUNK = 100;

@Component({
  selector: 'app-anime-detail',
  imports: [RouterLink],
  template: `
    @let a = anime();
    @if (a.error) {
      <div class="alert alert-error" role="alert">
        <p>{{ a.status === 404 ? 'Cet animé n’existe pas ou n’a plus d’épisode disponible.' : a.error }}</p>
        <p><a routerLink="/anime">Retour à la bibliothèque</a></p>
      </div>
    } @else if (a.data; as anime) {
      <p class="back"><a routerLink="/anime">‹ Bibliothèque</a></p>
      <h1>{{ anime.title }}</h1>
      @if (anime.alternativeTitle) { <p class="muted">{{ anime.alternativeTitle }}</p> }
      @if (anime.synopsis) { <p class="synopsis">{{ anime.synopsis }}</p> }

      @if (anime.seasons.length > 1) {
        <nav aria-label="Saisons" class="seasons">
          <ul>
            @for (s of anime.seasons; track s.id) {
              <li>
                <a [routerLink]="[]" [queryParams]="{ saison: s.seasonNumber }" replaceUrl
                   [class.current]="s.id === season()?.id" [attr.aria-current]="s.id === season()?.id ? 'true' : null">
                  {{ s.label }} <span class="count">{{ s.episodeCount }}</span>
                </a>
              </li>
            }
          </ul>
        </nav>
      }

      @if (season(); as s) {
        <section aria-labelledby="season-title">
          <h2 id="season-title">{{ s.label }} <span class="muted count-title">· {{ s.episodeCount }} épisode{{ s.episodeCount > 1 ? 's' : '' }}</span></h2>
          @let e = episodes();
          @if (e.error) {
            <div class="alert alert-error" role="alert"><p>{{ e.error }}</p></div>
          } @else if (e.data; as list) {
            @if (chunks().length > 1) {
              <div class="chunks" role="group" aria-label="Plages d’épisodes">
                @for (c of chunks(); track $index) {
                  <button type="button" class="btn-small" [class.current]="$index === chunk()"
                          [attr.aria-pressed]="$index === chunk()" (click)="chunk.set($index)">{{ c.label }}</button>
                }
              </div>
            }
            <ol class="episodes" [attr.aria-busy]="e.loading">
              @for (ep of visible(); track ep.id) {
                <li class="episode">
                  <span class="number" aria-hidden="true">{{ ep.episodeNumber }}</span>
                  <span class="ep-title">
                    <span class="visually-hidden">Épisode {{ ep.episodeNumber }}</span>
                    @if (ep.title) { {{ ep.title }} } @else { <span aria-hidden="true">Épisode {{ ep.episodeNumber }}</span> }
                    @if (ep.durationSeconds) { <span class="muted"> · {{ minutes(ep.durationSeconds) }} min</span> }
                  </span>
                </li>
              }
            </ol>
          } @else {
            <p class="muted" role="status">Chargement des épisodes…</p>
          }
        </section>
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .back { margin-bottom: var(--space-2); }
    .synopsis { max-width: 60rem; }
    .seasons ul, .chunks { display: flex; flex-wrap: wrap; gap: var(--space-2); margin: 0 0 var(--space-5); padding: 0; list-style: none; }
    .seasons a {
      display: inline-flex; align-items: center; gap: var(--space-2);
      min-height: var(--target-size); padding: 0 var(--space-4);
      border: 1px solid var(--color-border-strong); border-radius: var(--radius);
      color: var(--color-text); text-decoration: none;
    }
    .seasons a:hover { background: var(--color-surface-raised); color: var(--color-text); }
    .seasons a.current, .chunks .current { background: var(--color-accent); border-color: var(--color-accent); color: var(--color-on-accent); }
    .count { font-size: var(--font-size-xs); opacity: 0.8; }
    .count-title { font-size: var(--font-size-md); font-weight: var(--font-weight-normal); }
    .episodes {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
      gap: var(--space-2);
      margin: 0; padding: 0; list-style: none;
    }
    .episode {
      display: flex; align-items: center; gap: var(--space-3);
      min-height: var(--target-size); padding: var(--space-2) var(--space-3);
      border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface);
    }
    .number {
      flex: 0 0 auto; min-width: 2.5rem; text-align: center;
      font-weight: var(--font-weight-bold); font-variant-numeric: tabular-nums; color: var(--color-accent);
    }
    .ep-title { overflow-wrap: anywhere; }
  `,
})
export class AnimeDetailPage {
  private readonly api = inject(LibraryApi);

  /** Paramètre de route :id et query param ?saison= (numéro de saison, 0 = Spéciaux). */
  readonly id = input.required<string>();
  readonly saison = input<number | undefined, unknown>(undefined, {
    transform: (v: unknown) => (v === undefined || v === null || v === '' ? undefined : numberAttribute(v)),
  });

  protected readonly anime = loadOn(this.id, (id) => this.api.anime(id));

  /** Saison choisie, sinon la première (les Spéciaux sont toujours en dernier). */
  protected readonly season = computed<Season | null>(() => {
    const seasons = this.anime().data?.seasons ?? [];
    return seasons.find((s) => s.seasonNumber === this.saison()) ?? seasons[0] ?? null;
  });
  private readonly seasonId = computed(() => this.season()?.id ?? null);
  protected readonly episodes = loadOn(this.seasonId, (id) => (id === null ? of([]) : this.api.episodes(id)));

  protected readonly chunks = computed(() => chunksOf(this.episodes().data ?? []));
  /** Tranche affichée : remise à la première à chaque changement de saison. */
  protected readonly chunk = linkedSignal({ source: this.seasonId, computation: () => 0 });
  protected readonly visible = computed(() => {
    const list = this.episodes().data ?? [];
    return list.length > CHUNK ? list.slice(this.chunk() * CHUNK, (this.chunk() + 1) * CHUNK) : list;
  });

  constructor() {
    effect(() => {
      const title = this.anime().data?.title;
      if (title) {
        document.title = `${title} · Anime Server`;
      }
    });
  }

  minutes(seconds: number): number {
    return Math.round(seconds / 60);
  }
}

/** Tranches de CHUNK épisodes, libellées avec les vrais numéros (« 1–100 », « 101–200 »…). */
export function chunksOf(episodes: EpisodeSummary[]): { label: string }[] {
  const out: { label: string }[] = [];
  for (let i = 0; i < episodes.length && episodes.length > CHUNK; i += CHUNK) {
    const part = episodes.slice(i, i + CHUNK);
    out.push({ label: `${part[0].episodeNumber}–${part[part.length - 1].episodeNumber}` });
  }
  return out;
}
