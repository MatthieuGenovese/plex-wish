import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { APP_NAME } from './core/app-name';
import { TMDB_NOTICE } from './pages/about/about';
import { AccountMenu } from './shared/account-menu';
import { Icon } from './shared/icon';
import { SearchBox } from './shared/search-box';

/**
 * Coquille : barre du haut (bureau : marque, Accueil, Bibliothèque, recherche, menu Compte ; téléphone : marque
 * seule), barre du bas sur téléphone (Accueil, Rechercher, Bibliothèque, Compte), pied de page (sources, TMDB).
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AccountMenu, Icon, SearchBox],
  template: `
    <a class="skip-link" href="#contenu">Aller au contenu</a>
    @if (auth.user()) {
      <header class="topbar">
        <a class="brand" routerLink="/" [attr.aria-label]="appName + ', accueil'">
          <span class="logo"><app-icon name="play_arrow_fill" /></span><span class="brand-name">{{ appName }}</span>
        </a>
        <nav class="topnav" aria-label="Navigation principale">
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Accueil</a>
          <a routerLink="/anime" routerLinkActive="active" ariaCurrentWhenActive="page">Bibliothèque</a>
        </nav>
        <app-search-box class="topbar-search" />
        <app-account-menu (logout)="logout()" />
      </header>
    } @else {
      <header class="topbar topbar-guest">
        <a class="brand" routerLink="/"><span class="logo"><app-icon name="play_arrow_fill" /></span><span class="brand-name">{{ appName }}</span></a>
      </header>
    }
    <main id="contenu" tabindex="-1" [class.with-bottombar]="auth.user()">
      <router-outlet />
    </main>
    <footer class="app-footer" [class.with-bottombar]="auth.user()">
      Informations des animés : AniList et TMDB. <span lang="en">{{ tmdbNotice }}</span> · <a routerLink="/a-propos">À propos</a>
    </footer>
    @if (auth.user()) {
      <nav class="bottombar" aria-label="Navigation principale (téléphone)">
        <a routerLink="/" routerLinkActive #home="routerLinkActive" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">
          <span class="pill"><app-icon [name]="home.isActive ? 'home_fill' : 'home'" /></span><span class="lbl">Accueil</span></a>
        <a routerLink="/recherche" routerLinkActive ariaCurrentWhenActive="page">
          <span class="pill"><app-icon name="search" /></span><span class="lbl">Rechercher</span></a>
        <a routerLink="/anime" routerLinkActive #lib="routerLinkActive" ariaCurrentWhenActive="page">
          <span class="pill"><app-icon [name]="lib.isActive ? 'video_library_fill' : 'video_library'" /></span><span class="lbl">Bibliothèque</span></a>
        <a routerLink="/compte" routerLinkActive #acc="routerLinkActive" ariaCurrentWhenActive="page">
          <span class="pill"><app-icon [name]="acc.isActive ? 'account_circle_fill' : 'account_circle'" /></span><span class="lbl">Compte</span></a>
      </nav>
    }
  `,
})
export class App {
  protected readonly tmdbNotice = TMDB_NOTICE;
  protected readonly appName = APP_NAME;
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }
}
