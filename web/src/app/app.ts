import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <a class="skip-link" href="#contenu">Aller au contenu</a>
    <header class="app-header">
      <a class="brand" routerLink="/">Anime Server</a>
      @if (auth.user(); as user) {
        <nav aria-label="Navigation principale">
          <ul>
            <li><a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Accueil</a></li>
            <li><a routerLink="/anime" routerLinkActive="active">Bibliothèque</a></li>
            @if (auth.isAdmin()) {
              <li><a routerLink="/admin" routerLinkActive="active">Administration</a></li>
            }
          </ul>
        </nav>
        <div class="account">
          <span class="muted" data-testid="username">{{ user.username }}</span>
          <button type="button" class="btn-small" (click)="logout()">Se déconnecter</button>
        </div>
      }
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
      gap: var(--space-2) var(--space-5);
      padding: var(--space-2) var(--space-5);
      background: var(--color-surface);
      border-bottom: 1px solid var(--color-border);
    }
    .brand {
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-bold);
      color: var(--color-text);
      text-decoration: none;
      min-height: var(--target-size);
      display: inline-flex;
      align-items: center;
    }
    nav { flex: 1; }
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
      padding: 0 var(--space-3);
      border-radius: var(--radius);
      color: var(--color-text-muted);
      text-decoration: none;
    }
    nav a:hover { color: var(--color-text); background: var(--color-surface-raised); }
    nav a.active { color: var(--color-text); background: var(--color-surface-raised); box-shadow: inset 0 -3px 0 var(--color-accent); }
    .account { display: flex; align-items: center; gap: var(--space-3); margin-left: auto; }
    main { padding: var(--space-5); max-width: var(--content-width); margin: 0 auto; }
    main:focus { outline: none; }
    @media (max-width: 40rem) {
      .app-header, main { padding-left: var(--space-3); padding-right: var(--space-3); }
    }
  `,
})
export class App {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }
}
