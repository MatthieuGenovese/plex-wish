import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { APP_NAME } from './app-name';

/** Titre d'onglet : « Page · Nom de l'application » (le nom vient de APP_NAME). */
@Injectable({ providedIn: 'root' })
export class AppTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.title.setTitle(pageTitle(this.buildTitle(snapshot)));
  }
}

/** « Fiche · Anime Server », ou le nom seul. */
export function pageTitle(page: string | null | undefined): string {
  return page ? `${page} · ${APP_NAME}` : APP_NAME;
}
