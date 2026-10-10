import { Component, ElementRef, afterRenderEffect, computed, effect, inject, input, linkedSignal, numberAttribute, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { EpisodeSummary, Progress, Resume, Season } from '../../core/api-types';
import { LibraryApi } from '../../core/library-api';
import { pageTitle } from '../../core/title-strategy';
import { Icon } from '../../shared/icon';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';
import { StateBox } from '../../shared/state';
import { longEpisode, minutesLabel, percent, remainingMinutes } from '../../shared/viewing';
import { CastSection } from './cast-section';

/** Au-delà, les épisodes d'une saison sont présentés par plages (One Piece : 1 000+ épisodes). */
export const CHUNK = 100;
/** Au-delà, les saisons sont dans un menu plutôt qu'en boutons. */
const MAX_SEASON_BUTTONS = 5;

/**
 * Fiche d'un animé : héros (affiche nette sur l'affiche floutée, titres, année, saisons et épisodes, genres,
 * synopsis replié), encadré « où vous en êtes » (S5 : reprendre, épisode suivant, commencer, revoir), saisons,
 * plages d'épisodes, liste des épisodes avec leur état (vu, en cours, reste N min), distribution.
 * {@code ?episode=} (lien depuis l'accueil) : ouvre la bonne saison et la bonne plage, et met l'épisode en évidence.
 */
@Component({
  selector: 'app-anime-detail',
  imports: [RouterLink, Icon, Poster, StateBox, CastSection],
  template: `
    @let a = anime();
    @if (a.error) {
      <app-state [icon]="a.status === 404 ? 'sentiment_dissatisfied' : 'cloud_off'" [error]="a.status !== 404"
                 [heading]="a.status === 404 ? 'Cet animé n’existe pas ou n’a plus d’épisode disponible' : 'Impossible de charger la fiche'"
                 [message]="a.status === 404 ? null : a.error">
        <a class="btn btn-ghost" routerLink="/anime">Retour à la bibliothèque</a>
      </app-state>
    } @else if (a.data; as anime) {
      <section class="hero detail-hero" aria-labelledby="anime-title">
        @if (anime.posterLargeUrl ?? anime.posterUrl; as bg) {
          <img class="hero-bg" [src]="bg" alt="" decoding="async" referrerpolicy="no-referrer" />
        } @else {
          <span class="hero-bg hero-bg-ph"></span>
        }
        <app-poster class="hero-poster" [title]="anime.title" [url]="anime.posterLargeUrl ?? anime.posterUrl" [eager]="true" />
        <div class="hero-text">
          <h1 id="anime-title" class="hero-title">{{ anime.title }}</h1>
          @if (subtitle(); as sub) {
            <p class="hero-sub">{{ sub }}</p>
          }
          <p class="hero-meta num">{{ meta() }}</p>
          @if (anime.genres?.length) {
            <ul class="genre-chips" aria-label="Genres">
              @for (g of anime.genres; track g.genre) {
                <li><a class="chip chip-dark" routerLink="/anime" [queryParams]="{ genre: g.genre }">{{ g.label }}</a></li>
              }
            </ul>
          }
        </div>
        @if (resumeBox(); as r) {
          <div class="hero-cta resume-box">
            <p class="resume-label">{{ r.label }}</p>
            <p class="resume-ep">{{ r.episode }}</p>
            @if (r.pct !== null) {
              <p class="hero-progress num"><span class="progress" aria-hidden="true"><i [style.width.%]="r.pct"></i></span>{{ r.left }}</p>
            }
            <div class="hero-actions">
              <a class="btn btn-primary btn-lg" [routerLink]="['/regarder', r.target.episodeId]" data-testid="hero-play">
                <app-icon [name]="r.icon" />{{ r.action }}</a>
              <button type="button" class="btn btn-lg" (click)="goTo(r.target)">Voir dans la liste</button>
            </div>
          </div>
        }
      </section>

      @if (anime.synopsis) {
        <section class="synopsis-block" aria-label="Synopsis">
          <p class="synopsis" [class.clamped]="!expanded() && longSynopsis()" [attr.lang]="anime.synopsisLanguage" id="synopsis">{{ anime.synopsis }}</p>
          @if (longSynopsis()) {
            <button type="button" class="btn-link more" [attr.aria-expanded]="expanded()" aria-controls="synopsis"
                    (click)="expanded.set(!expanded())">{{ expanded() ? 'Réduire' : 'Lire la suite' }}</button>
          }
        </section>
      }
      @if (anime.metadataSource || anime.tmdbUrl) {
        <p class="source">
          @if (anime.synopsis && anime.synopsisLanguage === 'en') {
            <span data-testid="english-hint">Synopsis en anglais (pas de traduction française) · </span>
          }
          Sources :
          @if (anime.metadataSource) {
            @if (anime.metadataUrl) {
              <a [href]="anime.metadataUrl" target="_blank" rel="noopener noreferrer">{{ anime.metadataSource }}</a>
            } @else { {{ anime.metadataSource }} }
          }
          @if (anime.metadataSource && anime.tmdbUrl) { · }
          @if (anime.tmdbUrl) {
            <a [href]="anime.tmdbUrl" target="_blank" rel="noopener noreferrer">TMDB</a>
          }
        </p>
      }

      @if (season(); as s) {
        <section class="episodes-block" aria-labelledby="season-title">
          <div class="episodes-head">
            <h2 id="season-title">Épisodes</h2>
            @if (anime.seasons.length > 1 && anime.seasons.length <= maxSeasonButtons) {
              <div class="segmented seasons" role="group" aria-label="Saisons">
                @for (x of anime.seasons; track x.id) {
                  <a [routerLink]="[]" [queryParams]="{ saison: x.seasonNumber, episode: null }" queryParamsHandling="merge" replaceUrl
                     [attr.aria-current]="x.id === s.id ? 'true' : null">{{ x.label }}</a>
                }
              </div>
            } @else if (anime.seasons.length > maxSeasonButtons) {
              <label class="visually-hidden" for="season-select">Saison</label>
              <select id="season-select" class="select-chip" (change)="selectSeason($event)">
                @for (x of anime.seasons; track x.id) {
                  <option [value]="x.seasonNumber" [selected]="x.id === s.id">{{ x.label }} ({{ x.episodeCount }})</option>
                }
              </select>
            }
            @if (chunks().length > 1) {
              <label class="visually-hidden" for="chunk-select">Plage d’épisodes</label>
              <select id="chunk-select" class="select-chip" (change)="chunk.set(+$any($event.target).value)">
                @for (c of chunks(); track $index) {
                  <option [value]="$index" [selected]="$index === chunk()">Épisodes {{ c.label }}</option>
                }
              </select>
            }
            <span class="muted episodes-count num">{{ countLabel() }}</span>
          </div>

          @let e = episodes();
          @if (e.error) {
            <app-state icon="cloud_off" heading="Impossible de charger les épisodes" [message]="e.error" [error]="true">
              <button type="button" class="btn-primary" (click)="episodes.reload()"><app-icon name="refresh" />Réessayer</button>
            </app-state>
          } @else if (e.data; as list) {
            @if (notInBrowser(list) > 0) {
              <p class="alert browser-note" data-testid="browser-note">
                {{ notInBrowser(list) === list.length ? 'Les épisodes de cette saison sont' : notInBrowser(list) + ' épisode' + (notInBrowser(list) > 1 ? 's sont' : ' est') }}
                dans un format que le navigateur ne lit pas toujours (à convertir, ou selon le navigateur) : en cas de problème,
                regardez-les avec l’application Android.
              </p>
            }
            <ol class="episodes" [attr.aria-busy]="e.loading">
              @for (ep of visible(); track ep.id) {
                @let p = progressOf(ep.id);
                <li class="episode" [id]="'ep-' + ep.id" [class.is-seen]="p?.completed" [class.is-target]="ep.id === highlighted()"
                    [attr.tabindex]="ep.id === highlighted() ? -1 : null">
                  <span class="ep-num num" aria-hidden="true">{{ ep.episodeNumber }}
                    @if (p && !p.completed && p.positionSeconds > 0) {
                      <span class="progress"><i [style.width.%]="pct(p)"></i></span>
                    }
                  </span>
                  <a class="ep-body ep-link" [routerLink]="['/regarder', ep.id]">
                    <span class="visually-hidden">Lire l’épisode {{ ep.episodeNumber }}</span>
                    @if (ep.title) {
                      <span class="ep-title">{{ ep.title }}</span>
                    } @else {
                      <span class="ep-title untitled" aria-hidden="true">Épisode {{ ep.episodeNumber }}</span>
                    }
                    <span class="ep-meta num">
                      {{ duration(ep.durationSeconds) ?? 'durée inconnue' }}
                      @if (ep.browserPlayable === false && notInBrowser(list) < list.length) {
                        <span class="badge">Android conseillé</span><span class="visually-hidden">, format que le navigateur ne lit pas toujours</span>
                      }
                    </span>
                  </a>
                  <span class="ep-state">
                    @if (p?.completed) {
                      <app-icon name="check_circle_fill" class="seen-icon" /><span class="visually-hidden">Vu</span>
                    } @else if (p && p.positionSeconds > 0) {
                      <span class="num">reste {{ left(p) }} min</span>
                    }
                  </span>
                </li>
              }
            </ol>
          } @else {
            <ol class="episodes" aria-hidden="true">
              @for (i of [1, 2, 3, 4, 5]; track i) {
                <li class="episode"><span class="skeleton ep-num"></span><span class="ep-body"><span class="skeleton skeleton-line" style="width:60%"></span></span></li>
              }
            </ol>
            <p class="visually-hidden" role="status">Chargement des épisodes…</p>
          }
        </section>
      }
      <app-cast-section [animeId]="id()" />
    } @else {
      <div class="hero detail-hero hero-skeleton" aria-hidden="true">
        <span class="skeleton hero-poster"></span>
        <div class="hero-text"><span class="skeleton skeleton-line" style="width: 60%; height: 2rem"></span>
          <span class="skeleton skeleton-line" style="width: 35%"></span></div>
      </div>
      <p class="visually-hidden" role="status">Chargement…</p>
    }
  `,
})
export class AnimeDetailPage {
  private readonly api = inject(LibraryApi);
  private readonly host = inject(ElementRef<HTMLElement>);
  protected readonly maxSeasonButtons = MAX_SEASON_BUTTONS;

  /** Paramètre de route :id et query params ?saison= (0 = Spéciaux) et ?episode= (épisode à mettre en évidence). */
  readonly id = input.required<string>();
  readonly saison = input<number | undefined, unknown>(undefined, { transform: optionalNumber });
  readonly episode = input<number | undefined, unknown>(undefined, { transform: optionalNumber });

  protected readonly anime = loadOn(this.id, (id) => this.api.anime(id));
  private readonly progress = loadOn(this.id, (id) => this.api.progress(id).pipe(catchError(() => of([] as Progress[]))));
  private readonly progressById = computed(() => new Map((this.progress().data ?? []).map((p) => [p.episodeId, p])));
  protected readonly expanded = signal(false);
  protected readonly highlighted = computed(() => this.episode() ?? null);
  /** Incrémenté par « Aller à l'épisode » : ramène l'épisode à l'écran même s'il était déjà en évidence. */
  private readonly scrollRequest = signal(0);
  private readonly router = inject(Router);

  /** Titre français (TMDB), autre titre : sans répéter le titre principal. */
  protected readonly subtitle = computed(() => {
    const a = this.anime().data;
    if (!a) return null;
    const seen = new Set([a.title.toLowerCase()]);
    const parts: string[] = [];
    for (const t of [a.frenchTitle, a.alternativeTitle]) {
      if (t && !seen.has(t.toLowerCase())) {
        seen.add(t.toLowerCase());
        parts.push(t);
      }
    }
    return parts.length ? parts.join(' · ') : null;
  });

  protected readonly meta = computed(() => {
    const a = this.anime().data;
    if (!a) return '';
    const regular = a.seasons.filter((s) => s.seasonNumber > 0).length;
    const episodes = a.seasons.reduce((n, s) => n + s.episodeCount, 0);
    return [a.year, regular > 1 ? `${regular} saisons` : null, `${episodes} épisode${episodes > 1 ? 's' : ''}`]
      .filter(Boolean).join(' · ');
  });

  protected readonly longSynopsis = computed(() => (this.anime().data?.synopsis?.length ?? 0) > 240);

  /** Saison affichée : celle de l'URL, sinon celle de l'épisode à reprendre, sinon la première (Spéciaux en dernier). */
  protected readonly season = computed<Season | null>(() => {
    const a = this.anime().data;
    const seasons = a?.seasons ?? [];
    const wanted = this.saison() ?? a?.resume?.seasonNumber;
    return seasons.find((s) => s.seasonNumber === wanted) ?? seasons[0] ?? null;
  });
  private readonly seasonId = computed(() => this.season()?.id ?? null);
  protected readonly episodes = loadOn(this.seasonId, (id) => (id === null ? of([]) : this.api.episodes(id)));

  protected readonly chunks = computed(() => chunksOf(this.episodes().data ?? []));
  /** Plage affichée : celle de l'épisode en évidence (ou à reprendre), sinon la première. */
  protected readonly chunk = linkedSignal({
    source: () => ({ list: this.episodes().data, target: this.highlighted() ?? this.anime().data?.resume?.episodeId }),
    computation: ({ list, target }) => {
      const i = (list ?? []).findIndex((e) => e.id === target);
      return i < 0 ? 0 : Math.floor(i / CHUNK);
    },
  });
  protected readonly visible = computed(() => {
    const list = this.episodes().data ?? [];
    return list.length > CHUNK ? list.slice(this.chunk() * CHUNK, (this.chunk() + 1) * CHUNK) : list;
  });

  protected readonly countLabel = computed(() => {
    const s = this.season();
    if (!s) return '';
    const seen = (this.episodes().data ?? []).filter((e) => this.progressById().get(e.id)?.completed).length;
    return `${s.episodeCount} épisode${s.episodeCount > 1 ? 's' : ''}` + (seen ? ` · ${seen} vu${seen > 1 ? 's' : ''}` : '');
  });

  /** Encadré « où vous en êtes » (S5). */
  protected readonly resumeBox = computed(() => {
    const r: Resume | null | undefined = this.anime().data?.resume;
    if (!r) return null;
    const episode = longEpisode(r.seasonNumber, r.episodeNumber) + (r.episodeTitle ? ` · ${r.episodeTitle}` : '');
    const resume = r.kind === 'RESUME' && r.durationSeconds > 0;
    const left = resume ? remainingMinutes(r.positionSeconds, r.durationSeconds) : null;
    const texts: Record<string, [string, string]> = {
      RESUME: ['Vous en êtes à', 'Reprendre'],
      NEXT: ['Prochain épisode', 'Regarder'],
      START: ['Pour commencer', 'Regarder le premier épisode'],
      REWATCH: ['Vous avez tout vu', 'Revoir depuis le début'],
    };
    return {
      label: texts[r.kind][0], action: texts[r.kind][1], episode, target: r,
      icon: (r.kind === 'REWATCH' ? 'replay' : 'play_arrow_fill') as 'replay' | 'play_arrow_fill',
      pct: resume ? percent(r.positionSeconds, r.durationSeconds) : null, left: left ? `reste ${left} min` : '',
    };
  });

  constructor() {
    effect(() => {
      const title = this.anime().data?.title;
      if (title) document.title = pageTitle(title);
    });
    // Épisode mis en évidence : amené à l'écran et focalisé (clavier, lecteur d'écran) une fois la liste affichée.
    afterRenderEffect(() => {
      const target = this.highlighted();
      const request = this.scrollRequest();
      if (target === null || !this.visible().some((e) => e.id === target)) return;
      const row = (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>(`#ep-${target}`);
      if (row && row.dataset['shown'] !== String(request)) {
        row.dataset['shown'] = String(request);
        row.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
        row.focus({ preventScroll: true });
      }
    });
  }

  /** « Aller à l'épisode » : bonne saison et bonne plage (par l'URL), épisode amené à l'écran et focalisé. */
  goTo(r: Resume): void {
    this.router.navigate([], { queryParams: { saison: r.seasonNumber, episode: r.episodeId }, queryParamsHandling: 'merge', replaceUrl: true });
    this.scrollRequest.update((n) => n + 1);
  }

  selectSeason(event: Event): void {
    const n = Number((event.target as HTMLSelectElement).value);
    this.router.navigate([], { queryParams: { saison: n, episode: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected progressOf(id: number): Progress | undefined {
    return this.progressById().get(id);
  }

  protected pct(p: Progress): number {
    return percent(p.positionSeconds, p.durationSeconds);
  }

  protected left(p: Progress): number | null {
    return remainingMinutes(p.positionSeconds, p.durationSeconds);
  }

  protected duration(seconds: number | null): string | null {
    return minutesLabel(seconds);
  }

  /** Épisodes dont le format n'est pas lisible dans un navigateur (analyse du fichier). */
  notInBrowser(list: EpisodeSummary[]): number {
    return list.filter((e) => e.browserPlayable === false).length;
  }
}

function optionalNumber(v: unknown): number | undefined {
  return v === undefined || v === null || v === '' ? undefined : numberAttribute(v);
}

/** Plages de CHUNK épisodes, libellées avec les vrais numéros (« 1–100 », « 101–200 »…). */
export function chunksOf(episodes: EpisodeSummary[]): { label: string }[] {
  const out: { label: string }[] = [];
  for (let i = 0; i < episodes.length && episodes.length > CHUNK; i += CHUNK) {
    const part = episodes.slice(i, i + CHUNK);
    out.push({ label: `${part[0].episodeNumber}–${part[part.length - 1].episodeNumber}` });
  }
  return out;
}
