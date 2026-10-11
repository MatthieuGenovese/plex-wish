import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ExternalSubtitle, SubtitleFilter } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';

const PAGE_SIZE = 50;

export const SUBTITLE_FILTERS: { value: SubtitleFilter; label: string }[] = [
  { value: 'ALL', label: 'Tous' },
  { value: 'UNMATCHED', label: 'Sans épisode' },
  { value: 'MANUAL', label: 'Corrigés' },
  { value: 'DURATION', label: 'Trop longs' },
];

const MATCH_LABELS: Record<string, string> = {
  SAME_NAME: 'même nom que la vidéo',
  NAME: 'numéro lu dans le nom',
  MANUAL: 'correction',
};

/** « 23:41 » ou « 1:02:03 ». */
export function clock(seconds: number | null): string {
  if (seconds === null) return '—';
  const s = Math.round(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/**
 * Sous-titres externes (.ass posés à côté des vidéos, 1.3.0) : association trouvée au scan, contrôle de durée, et
 * correction manuelle (rattacher à un épisode, détacher, revenir à l'automatique), effective tout de suite et gardée
 * par les scans.
 */
@Component({
  selector: 'app-admin-subtitles',
  imports: [Pager],
  template: `
    <section aria-labelledby="subs-title" id="sous-titres">
      <h2 id="subs-title">Sous-titres externes</h2>
      <p class="muted">Fichiers .ass posés à côté des vidéos : associés au scan par leur nom (même nom que la vidéo, sinon numéro d’épisode).
        Une correction s’applique tout de suite et n’est jamais écrasée par un scan.</p>
      @let l = list();
      @if (l.data?.summary; as s) {
        <p class="muted" data-testid="subs-summary">{{ num(s.total) }} fichier{{ s.total > 1 ? 's' : '' }} · {{ num(s.linked) }} associé{{ s.linked > 1 ? 's' : '' }} ·
          {{ num(s.unmatched) }} sans épisode · {{ num(s.manual) }} corrigé{{ s.manual > 1 ? 's' : '' }} · {{ num(s.tooLong) }} trop long{{ s.tooLong > 1 ? 's' : '' }}</p>
      }
      <form class="filters" (submit)="$event.preventDefault()">
        <div class="field">
          <label for="subs-filter">Afficher</label>
          <select id="subs-filter" (change)="setFilter($any($event.target).value)">
            @for (f of filters; track f.value) { <option [value]="f.value" [selected]="filter() === f.value">{{ f.label }}</option> }
          </select>
        </div>
        <div class="field">
          <label for="subs-anime">Chemin</label>
          <input id="subs-anime" type="search" autocomplete="off" placeholder="Filtrer par dossier ou nom" [value]="anime()"
                 (input)="onAnime($any($event.target).value)" />
        </div>
      </form>

      <div aria-live="polite">
        @if (message(); as m) {
          <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
        }
      </div>

      @if (editing(); as e) {
        <form class="attach" (submit)="$event.preventDefault(); save()" aria-labelledby="attach-title">
          <h3 id="attach-title">Rattacher <span class="path">{{ e.relativePath }}</span></h3>
          <div class="row">
            <div class="field">
              <label for="attach-anime">Animé</label>
              <input id="attach-anime" [value]="form().animeTitle" (input)="patch({ animeTitle: $any($event.target).value })" />
            </div>
            <div class="field small">
              <label for="attach-season">Saison</label>
              <input id="attach-season" type="number" min="0" max="99" required [value]="form().seasonNumber"
                     (input)="patch({ seasonNumber: $any($event.target).value })" />
            </div>
            <div class="field small">
              <label for="attach-episode">Épisode</label>
              <input id="attach-episode" type="number" min="0" max="9999" required [value]="form().episodeNumber"
                     (input)="patch({ episodeNumber: $any($event.target).value })" />
            </div>
          </div>
          <div class="actions">
            <button type="submit" class="btn-primary" [disabled]="busy()">Rattacher</button>
            <button type="button" (click)="editing.set(null)" [disabled]="busy()">Annuler</button>
          </div>
        </form>
      }

      @if (l.error) {
        <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
      } @else if (l.data; as page) {
        @if (page.items.length === 0) {
          <p class="alert">Aucun sous-titre externe{{ filter() === 'ALL' ? '' : ' dans cette catégorie' }}.</p>
        } @else {
          <div class="table-wrap" [attr.aria-busy]="l.loading">
            <table class="wide">
              <thead>
                <tr>
                  <th scope="col">Chemin</th><th scope="col">Épisode</th><th scope="col">Association</th>
                  <th scope="col">Dernier texte / vidéo</th><th scope="col"><span class="visually-hidden">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                @for (x of page.items; track x.id) {
                  <tr>
                    <td class="path">{{ x.relativePath }} @if (x.forced) { <span class="badge">forcés</span> }</td>
                    <td>{{ episodeText(x) }}</td>
                    <td>{{ matchText(x) }}</td>
                    <td class="nowrap" [class.warn]="x.tooLong">
                      {{ clock(x.lastCueSeconds) }} / {{ clock(x.videoDurationSeconds) }}
                      @if (x.tooLong) { <span class="visually-hidden">(dépasse la vidéo)</span> <span aria-hidden="true">⚠</span> }
                    </td>
                    <td class="buttons">
                      <button type="button" class="btn-small" (click)="edit(x)" [disabled]="busy()"
                              [attr.aria-label]="'Rattacher ' + x.relativePath">Rattacher…</button>
                      @if (x.episode) {
                        <button type="button" class="btn-small" (click)="detach(x)" [disabled]="busy()"
                                [attr.aria-label]="'Détacher ' + x.relativePath">Détacher</button>
                      }
                      @if (x.manual) {
                        <button type="button" class="btn-small" (click)="automatic(x)" [disabled]="busy()"
                                [attr.aria-label]="'Association automatique pour ' + x.relativePath">Automatique</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages des sous-titres externes"
                     (pageChange)="page$.set($event)" />
        }
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
    </section>
  `,
  styles: `
    section { margin-top: var(--space-6); }
    .filters, .row { display: flex; flex-wrap: wrap; gap: var(--space-3); }
    .filters .field, .row .field { flex: 1 1 14rem; max-width: 24rem; }
    .row .field.small { flex: 0 1 7rem; }
    .attach { margin: var(--space-4) 0; padding: var(--space-4); border: 1px solid var(--color-border); border-radius: var(--radius);
      background: var(--color-surface); }
    .attach h3 { margin-top: 0; font-size: var(--font-size-md); word-break: break-word; }
    .actions, .buttons { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .actions { margin-top: var(--space-3); }
    .nowrap { white-space: nowrap; }
    .warn { color: var(--color-warning); }
    .badge { margin-left: var(--space-1); padding: 0 var(--space-1); border: 1px solid var(--color-border); border-radius: var(--radius);
      font-size: var(--font-size-sm); }
  `,
})
export class SubtitlesSection {
  private readonly api = inject(AdminApi);
  /** Filtre de départ (lien du rapport de scan). */
  readonly initialFilter = input<string | undefined>(undefined);

  protected readonly filters = SUBTITLE_FILTERS;
  protected readonly num = formatNumber;
  protected readonly clock = clock;
  protected readonly filter = signal<SubtitleFilter>('ALL');
  protected readonly anime = signal('');
  protected readonly page$ = signal(0);
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly editing = signal<ExternalSubtitle | null>(null);
  protected readonly form = signal({ animeTitle: '', seasonNumber: '1', episodeNumber: '' });
  private animeTimer: ReturnType<typeof setTimeout> | undefined;

  private readonly query = computed(() => ({
    filter: this.filter(),
    anime: this.anime().trim() || undefined,
    page: this.page$(),
    size: PAGE_SIZE,
  }));
  protected readonly list = loadOn(this.query, (q) => this.api.subtitles(q));

  constructor() {
    effect(() => {
      const f = this.initialFilter();
      if (f && SUBTITLE_FILTERS.some((x) => x.value === f)) untracked(() => this.setFilter(f));
    });
  }

  setFilter(value: string): void {
    this.filter.set(value as SubtitleFilter);
    this.page$.set(0);
  }

  onAnime(value: string): void {
    clearTimeout(this.animeTimer);
    this.animeTimer = setTimeout(() => {
      this.anime.set(value);
      this.page$.set(0);
    }, 300);
  }

  episodeText(x: ExternalSubtitle): string {
    const e = x.episode;
    return e ? `${e.animeTitle} · saison ${e.seasonNumber} · épisode ${e.episodeNumber}` : '—';
  }

  matchText(x: ExternalSubtitle): string {
    if (x.manual && !x.episode) return 'détaché (correction)';
    return x.match ? MATCH_LABELS[x.match] : 'aucun épisode trouvé';
  }

  edit(x: ExternalSubtitle): void {
    this.message.set(null);
    this.form.set({
      animeTitle: x.episode?.animeTitle ?? x.animeFolder ?? '',
      seasonNumber: String(x.episode?.seasonNumber ?? 1),
      episodeNumber: x.episode ? String(x.episode.episodeNumber) : '',
    });
    this.editing.set(x);
  }

  patch(p: Partial<{ animeTitle: string; seasonNumber: string; episodeNumber: string }>): void {
    this.form.update((f) => ({ ...f, ...p }));
  }

  save(): void {
    const x = this.editing();
    const f = this.form();
    const season = Number(f.seasonNumber);
    const episode = Number(f.episodeNumber);
    if (!x || f.seasonNumber === '' || f.episodeNumber === '' || !Number.isInteger(season) || !Number.isInteger(episode)) {
      this.message.set({ text: 'Indiquez une saison et un épisode (nombres entiers).', error: true });
      return;
    }
    this.run(this.api.attachSubtitle(x.id, { animeTitle: f.animeTitle.trim() || undefined, seasonNumber: season, episodeNumber: episode }),
      (r) => `Rattaché à ${this.episodeText(r)}.`);
  }

  detach(x: ExternalSubtitle): void {
    this.run(this.api.detachSubtitle(x.id), () => `${x.relativePath} : détaché, il ne sera plus proposé.`);
  }

  automatic(x: ExternalSubtitle): void {
    this.run(this.api.automaticSubtitle(x.id), (r) =>
      r.episode ? `Association automatique : ${this.episodeText(r)}.` : 'Association automatique : aucun épisode trouvé.');
  }

  private run(call: ReturnType<AdminApi['detachSubtitle']>, text: (r: ExternalSubtitle) => string): void {
    this.busy.set(true);
    call.subscribe({
      next: (r) => {
        this.busy.set(false);
        this.editing.set(null);
        this.message.set({ text: text(r), error: false });
        this.list.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }
}
