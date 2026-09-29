import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip-link" href="#contenu">Aller au contenu</a>
    <header class="app-header">
      <a class="brand" routerLink="/">Anime Server</a>
      <nav aria-label="Navigation principale">
        <ul>
          <li><a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Accueil</a></li>
          <li><a routerLink="/anime" routerLinkActive="active">Bibliothèque</a></li>
          <li><a routerLink="/admin" routerLinkActive="active">Admin</a></li>
          <li><a routerLink="/login" routerLinkActive="active">Connexion</a></li>
        </ul>
      </nav>
    </header>
    <main id="contenu" tabindex="-1">
      <router-outlet />
    </main>
  `,
  styles: `
    .app-header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      background: var(--surface);
      border-bottom: 1px solid var(--border);
    }
    .brand {
      font-size: 1.25rem;
      font-weight: 700;
      color: var(--text);
      text-decoration: none;
    }
    ul {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    nav a {
      display: inline-flex;
      align-items: center;
      min-height: var(--target-size);
      padding: 0 var(--space-2);
      border-radius: var(--radius);
      color: var(--text-muted);
      text-decoration: none;
    }
    nav a:hover { color: var(--text); background: var(--surface-raised); }
    nav a.active { color: var(--text); background: var(--surface-raised); box-shadow: inset 0 -3px 0 var(--accent); }
    main { padding: var(--space-3); max-width: 1200px; margin: 0 auto; }
    main:focus { outline: none; }
  `,
})
export class App {}
