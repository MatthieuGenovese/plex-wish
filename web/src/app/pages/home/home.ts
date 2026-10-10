import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, map, of, switchMap, catchError } from 'rxjs';
import { AnimeSummary, ContinueWatching, GenreCount } from '../../core/api-types';
import { AuthService } from '../../core/auth.service';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { AnimeCard } from '../../shared/anime-card';
import { Icon } from '../../shared/icon';
import { Poster } from '../../shared/poster';
import { Rail } from '../../shared/rail';
import { ResumeCard } from '../../shared/resume-card';
import { PlaybackApi } from '../../core/playback-api';
import { StateBox } from '../../shared/state';
import { longEpisode, percent, remainingMinutes } from '../../shared/viewing';

/** Genres jamais proposés en rangée d'accueil (contenus pour adultes). */
const HIDDEN_GENRES = new Set(['Hentai', 'Ecchi']);
/** Cartes par rangée : assez pour défiler, pas plus (DOM et images : la rangée « Tout voir » mène à la suite). */
const ROW = 12;

interface HomeData {
  continueWatching: ContinueWatching[];
  recent: { total: number; items: AnimeSummary[] };
  discover: AnimeSummary[];
  genres: { genre: GenreCount; items: AnimeSummary[] }[];
}

/**
 * Accueil : héros « À reprendre » (dernier épisode commencé ou épisode suivant), puis rangées « Continuer à
 * regarder », « Récemment ajoutés », « À découvrir » (page tirée au hasard) et trois genres. Sans historique :
 * le dernier ajout en héros. Bibliothèque vide : un état explicite.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, AnimeCard, Icon, Poster, Rail, ResumeCard, StateBox],
  template: `
    <h1 class="visually-hidden">Accueil</h1>
    @let s = state();
    @if (s.error) {
      <app-state icon="cloud_off" heading="Impossible de charger l’accueil" [message]="s.error" [error]="true">
        <button type="button" class="btn-primary" (click)="state.reload()"><app-icon name="refresh" />Réessayer</button>
      </app-state>
    } @else if (s.data; as d) {
      @if (d.recent.total === 0) {
        <app-state icon="video_library" heading="La bibliothèque est vide"
                   [message]="auth.isAdmin() ? 'Lancez un scan pour importer les animés du dossier média.' : 'L’administrateur n’a pas encore importé d’animés. Revenez un peu plus tard.'">
          @if (auth.isAdmin()) {
            <a class="btn btn-primary" routerLink="/admin/scan">Lancer un scan</a>
          }
        </app-state>
      } @else {
        @if (hero(); as h) {
          <section class="hero" aria-labelledby="hero-title">
            @if (h.poster) {
              <img class="hero-bg" [src]="h.poster" alt="" decoding="async" referrerpolicy="no-referrer" />
            } @else {
              <span class="hero-bg hero-bg-ph"></span>
            }
            <app-poster class="hero-poster" [title]="h.title" [url]="h.poster" [eager]="true" />
            <div class="hero-text">
              <p class="hero-kicker">{{ h.kicker }}</p>
              <h2 id="hero-title" class="hero-title">{{ h.title }}</h2>
              <p class="hero-meta num">{{ h.meta }}
                @if (h.episodeId && webStatus()[h.episodeId] === 'READY') { <span class="badge badge-success">Prêt pour le navigateur</span> }
                @else if (h.episodeId && webStatus()[h.episodeId] === 'PREPARING') { <span class="badge">En préparation</span> }
              </p>
              @if (h.pct !== null) {
                <p class="hero-progress num"><span class="progress" aria-hidden="true"><i [style.width.%]="h.pct"></i></span>{{ h.left }}</p>
              }
            </div>
            <div class="hero-cta">
              <div class="hero-actions">
                <a class="btn btn-primary btn-lg" [routerLink]="h.link" [queryParams]="h.params" data-testid="hero-play">
                  <app-icon [name]="h.icon" />{{ h.action }}</a>
              </div>
            </div>
          </section>
        }

        @if (d.continueWatching.length > 1) {
          <app-rail heading="Continuer à regarder" id="rail-continue">
            <ul class="rail-list">
              @for (c of d.continueWatching.slice(1); track c.animeId) {
                <li><app-resume-card [item]="c" [webState]="webStatus()[c.episodeId]" /></li>
              }
            </ul>
          </app-rail>
        }

        <app-rail heading="Récemment ajoutés" id="rail-recent" moreLink="/anime" [moreParams]="{ tri: 'recent' }">
          <ul class="rail-list">
            @for (a of d.recent.items; track a.id) {
              <li><app-anime-card [anime]="a" [showNew]="false" /></li>
            }
          </ul>
        </app-rail>

        @for (g of d.genres; track g.genre.genre) {
          <app-rail [heading]="g.genre.label" [id]="'rail-genre-' + $index" moreLink="/anime" [moreParams]="{ genre: g.genre.genre }">
            <ul class="rail-list">
              @for (a of g.items; track a.id) {
                <li><app-anime-card [anime]="a" /></li>
              }
            </ul>
          </app-rail>
        }

        @if (d.discover.length > 0) {
          <app-rail heading="À découvrir" id="rail-discover" moreLink="/anime">
            <ul class="rail-list">
              @for (a of d.discover; track a.id) {
                <li><app-anime-card [anime]="a" /></li>
              }
            </ul>
          </app-rail>
        }
      }
    } @else {
      <div class="hero hero-skeleton" aria-hidden="true">
        <span class="skeleton hero-poster"></span>
        <div class="hero-text"><span class="skeleton skeleton-line" style="width: 30%"></span>
          <span class="skeleton skeleton-line" style="width: 70%; height: 2rem"></span><span class="skeleton skeleton-line" style="width: 45%"></span></div>
      </div>
      <p class="visually-hidden" role="status">Chargement de l’accueil…</p>
      <div class="rail-skeleton" aria-hidden="true">
        @for (i of skeletons; track i) {
          <span class="skeleton"></span>
        }
      </div>
    }
  `,
})
export class HomePage {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(LibraryApi);
  private readonly once = signal(0);
  protected readonly skeletons = [1, 2, 3, 4, 5, 6, 7];

  protected readonly state = loadOn(this.once, () =>
    forkJoin({
      continueWatching: this.api.continueWatching(ROW + 1).pipe(catchError(() => of([] as ContinueWatching[]))),
      recent: this.api.animes({ sort: 'recent', size: ROW }),
      genres: this.api.genres().pipe(catchError(() => of([] as GenreCount[]))),
    }).pipe(
      switchMap(({ continueWatching, recent, genres }) => {
        // « À découvrir » : une page tirée au hasard dans la bibliothèque (ordre alphabétique).
        const pages = Math.max(1, Math.ceil(recent.total / ROW));
        const top = genres.filter((g) => !HIDDEN_GENRES.has(g.genre) && g.animeCount >= 4)
          .sort((a, b) => b.animeCount - a.animeCount).slice(0, 3);
        const rows = top.map((g) => this.api.animes({ genre: g.genre, sort: 'recent', size: ROW }).pipe(
          map((p) => ({ genre: g, items: p.items })), catchError(() => of({ genre: g, items: [] as AnimeSummary[] }))));
        const discover = recent.total > ROW
          ? this.api.animes({ sort: 'title', size: ROW, page: Math.floor(Math.random() * pages) }).pipe(map((p) => p.items), catchError(() => of([])))
          : of([] as AnimeSummary[]);
        return forkJoin({ discover, genres: rows.length ? forkJoin(rows) : of([]) }).pipe(
          map((extra): HomeData => ({ continueWatching, recent: { total: recent.total, items: recent.items }, discover: extra.discover,
            genres: extra.genres.filter((g) => g.items.length > 0) })),
        );
      }),
    ),
  );

  private readonly playback = inject(PlaybackApi);
  /** Épisodes de « Continuer » → « Prêt pour le navigateur » / « En préparation » (D9). */
  private readonly resumeIds = computed(() => (this.state().data?.continueWatching ?? []).map((c) => c.episodeId).join(','));
  private readonly webStatusState = loadOn(this.resumeIds, (ids) =>
    ids ? this.playback.webStatus(ids.split(',').map(Number)).pipe(catchError(() => of({}))) : of({}));
  protected readonly webStatus = computed<Record<string, 'READY' | 'PREPARING'>>(() => this.webStatusState().data ?? {});

  /** Héros : l'épisode à reprendre (la rangée « Continuer à regarder » montre les suivants) (ou suivant), sinon le dernier ajout. */
  protected readonly hero = computed(() => {
    const d = this.state().data;
    if (!d) return null;
    const c = d.continueWatching[0];
    if (c) {
      const resume = c.kind === 'RESUME' && c.durationSeconds > 0;
      const left = resume ? remainingMinutes(c.positionSeconds, c.durationSeconds) : null;
      return {
        animeId: c.animeId, title: c.animeTitle, poster: c.posterUrl,
        kicker: c.kind === 'NEXT' ? 'À suivre' : 'À reprendre',
        meta: longEpisode(c.seasonNumber, c.episodeNumber) + (c.episodeTitle ? ` · ${c.episodeTitle}` : ''),
        pct: resume ? percent(c.positionSeconds, c.durationSeconds) : null,
        left: left ? `reste ${left} min` : '',
        action: c.kind === 'NEXT' ? 'Regarder l’épisode suivant' : 'Reprendre', icon: 'play_arrow_fill' as const,
        link: ['/regarder', String(c.episodeId)], params: {}, episodeId: c.episodeId as number | null,
      };
    }
    const a = d.recent.items[0];
    if (!a) return null;
    return {
      animeId: a.id, title: a.title, poster: a.posterUrl, kicker: 'Dernier ajout',
      meta: [a.year, `${a.episodeCount} épisode${a.episodeCount > 1 ? 's' : ''}`].filter(Boolean).join(' · '),
      pct: null, left: '', action: 'Voir la fiche', icon: 'chevron_right' as const, link: ['/anime', String(a.id)], params: {},
      episodeId: null as number | null,
    };
  });
}
