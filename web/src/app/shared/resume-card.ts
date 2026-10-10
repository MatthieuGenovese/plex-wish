import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ContinueWatching } from '../core/api-types';
import { Icon } from './icon';
import { Poster } from './poster';
import { longEpisode, percent, remainingMinutes, shortEpisode } from './viewing';

/**
 * Carte « Continuer à regarder » (paysage) : affiche floutée en fond (pas d'image d'épisode sur le serveur),
 * affiche nette, titre, épisode et temps restant ; « Épisode suivant » pour un épisode à commencer.
 * Lance la lecture de cet épisode dans le lecteur web (phase 10).
 */
@Component({
  selector: 'app-resume-card',
  imports: [RouterLink, Poster, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let c = item();
    <a class="resume-card" [routerLink]="['/regarder', c.episodeId]" [attr.aria-label]="label()">
      @if (c.posterUrl) {
        <img class="resume-bg" [src]="c.posterUrl" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" />
      } @else {
        <span class="resume-bg resume-bg-ph" [style.--h]="hue()"></span>
      }
      <app-poster class="resume-poster" [title]="c.animeTitle" [url]="c.posterUrl" />
      <span class="resume-text">
        <span class="resume-kicker">{{ c.kind === 'NEXT' ? 'Épisode suivant' : 'Reprendre' }}</span>
        <span class="resume-title">{{ c.animeTitle }}</span>
        <span class="resume-meta num">{{ meta() }}</span>
        @if (c.kind === 'RESUME' && c.durationSeconds > 0) {
          <span class="progress" aria-hidden="true"><i [style.width.%]="pct()"></i></span>
        }
      </span>
      <span class="resume-go" aria-hidden="true"><app-icon name="chevron_right" /></span>
    </a>
  `,
  host: { class: 'resume-card-host' },
})
export class ResumeCard {
  readonly item = input.required<ContinueWatching>();
  protected readonly pct = computed(() => percent(this.item().positionSeconds, this.item().durationSeconds));
  protected readonly meta = computed(() => {
    const c = this.item();
    const ep = shortEpisode(c.seasonNumber, c.episodeNumber);
    const left = c.kind === 'RESUME' ? remainingMinutes(c.positionSeconds, c.durationSeconds) : null;
    return left ? `${ep} · reste ${left} min` : ep;
  });
  protected readonly label = computed(() => {
    const c = this.item();
    const left = c.kind === 'RESUME' ? remainingMinutes(c.positionSeconds, c.durationSeconds) : null;
    return `${c.kind === 'NEXT' ? 'Épisode suivant' : 'Reprendre'} : ${c.animeTitle}, ${longEpisode(c.seasonNumber, c.episodeNumber)}`
      + (left ? `, reste ${left} minutes` : '');
  });
  protected readonly hue = computed(() => {
    let h = 0;
    for (const ch of this.item().animeTitle) h = (h * 31 + ch.charCodeAt(0)) % 360;
    return h;
  });
}
