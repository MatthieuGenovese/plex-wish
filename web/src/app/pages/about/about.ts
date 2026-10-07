import { Component, signal } from '@angular/core';
import { APP_NAME } from '../../core/app-name';

/** Texte imposé par les conditions de l'API TMDB (§3.A), à afficher tel quel. */
export const TMDB_NOTICE =
  'This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.';

/**
 * À propos : sources des métadonnées et attribution TMDB (logo + mention obligatoire). Le logo reste plus petit
 * que le nom de l'application (conditions TMDB). Le fichier du logo officiel est à déposer dans
 * public/attribution/tmdb-logo.svg (voir le README de ce dossier) ; à défaut, le nom « TMDB » s'affiche en texte.
 */
@Component({
  selector: 'app-about',
  template: `
    <h1>À propos</h1>
    <p>{{ appName }} : serveur privé de streaming d’animés, pour un petit groupe d’amis.</p>

    <h2>Sources des informations</h2>
    <section class="card" aria-labelledby="about-anilist">
      <h3 id="about-anilist">AniList</h3>
      <p>Synopsis en anglais, années, personnages et comédiens de doublage viennent d’<a href="https://anilist.co" target="_blank" rel="noopener noreferrer">AniList</a>, ainsi que les affiches quand TMDB n’en a pas.</p>
    </section>
    <section class="card" aria-labelledby="about-tmdb">
      <h3 id="about-tmdb" class="tmdb-title">
        @if (!logoBroken()) {
          <img class="tmdb-logo" src="attribution/tmdb-logo.svg" alt="TMDB" (error)="logoBroken.set(true)" />
        } @else {
          TMDB
        }
      </h3>
      <p>Synopsis et titres en français : <a href="https://www.themoviedb.org" target="_blank" rel="noopener noreferrer">The Movie Database (TMDB)</a>.</p>
      <p class="notice" lang="en" data-testid="tmdb-notice">{{ notice }}</p>
    </section>
  `,
  styles: `
    .tmdb-title { display: flex; align-items: center; min-height: 1.5rem; }
    /* Plus discret que le nom de l'application (conditions TMDB). */
    .tmdb-logo { height: 1rem; width: auto; }
    .notice { color: var(--color-text-muted); font-size: var(--font-size-sm); }
  `,
})
export class AboutPage {
  protected readonly notice = TMDB_NOTICE;
  protected readonly appName = APP_NAME;
  protected readonly logoBroken = signal(false);
}
