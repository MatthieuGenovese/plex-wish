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
    <div class="about">
      <h1>À propos</h1>
      <p class="lead">{{ appName }} : serveur privé de streaming d’animés, pour un petit groupe d’amis.</p>

      <h2>Sources des informations</h2>
      <section class="panel" aria-labelledby="about-anilist">
        <h3 id="about-anilist">AniList</h3>
        <p>Synopsis en anglais, années, genres, personnages et comédiens de doublage viennent d’<a href="https://anilist.co" target="_blank" rel="noopener noreferrer">AniList</a>, ainsi que les affiches quand TMDB n’en a pas.</p>
      </section>
      <section class="panel" aria-labelledby="about-tmdb">
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
      <section class="panel" aria-labelledby="about-player">
        <h3 id="about-player">Lecteur vidéo</h3>
        <p>Lecture dans le navigateur : hls.js (licence Apache 2.0). Sous-titres ASS : JASSUB (MIT), avec libass,
          FreeType, HarfBuzz, FriBidi et leurs dépendances (LGPL 2.1, FTL, MIT et licences proches), servis par ce site.
          Préparation et conversion des épisodes sur le serveur : FFmpeg avec x264 (GPL 2 ou plus).</p>
        <p data-testid="source-notice">Le code source complet de ces composants, et celui de l’application, est fourni à
          toute personne qui le demande à l’administrateur du serveur.</p>
      </section>
      <section class="panel" aria-labelledby="about-credits">
        <h3 id="about-credits">Police et icônes</h3>
        <p>Police Figtree (SIL Open Font License 1.1) ; icônes Material Symbols (Google, licence Apache 2.0).</p>
      </section>
    </div>
  `,
  styles: `
    .tmdb-title { display: flex; align-items: center; min-height: 1.5rem; }
    /* Plus discret que le nom de l'application (conditions TMDB). */
    .tmdb-logo { height: 1rem; width: auto; }
    .notice { color: var(--text-2); font-size: var(--fs-small); margin: 0; }
    .about { max-width: 46rem; display: flex; flex-direction: column; gap: var(--space-4); }
    .about h2 { margin: var(--space-3) 0 0; }
    .about .panel h3 { margin-bottom: var(--space-2); }
    .about .panel p:last-child { margin-bottom: 0; }
    .lead { font-size: var(--fs-title); color: var(--text-2); }
  `,
})
export class AboutPage {
  protected readonly notice = TMDB_NOTICE;
  protected readonly appName = APP_NAME;
  protected readonly logoBroken = signal(false);
}
