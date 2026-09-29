import { Component, input } from '@angular/core';

@Component({
  selector: 'app-anime-detail',
  template: `
    <h1>Anime n° {{ id() }}</h1>
    <p>Fiche anime (saisons et épisodes) : à venir (phases 3 et 4).</p>
  `,
})
export class AnimeDetailPage {
  /** Paramètre de route :id (withComponentInputBinding). */
  readonly id = input.required<string>();
}
