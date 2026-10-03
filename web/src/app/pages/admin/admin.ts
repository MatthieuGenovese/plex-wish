import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-admin',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <h1>Administration</h1>
    <nav aria-label="Administration" class="tabs">
      <ul>
        <li><a routerLink="scan" routerLinkActive="active" ariaCurrentWhenActive="page">Scan</a></li>
        <li><a routerLink="report" routerLinkActive="active" ariaCurrentWhenActive="page">Rapport</a></li>
        <li><a routerLink="corrections" routerLinkActive="active" ariaCurrentWhenActive="page">Corrections</a></li>
        <li><a routerLink="metadata" routerLinkActive="active" ariaCurrentWhenActive="page">Métadonnées</a></li>
        <li><a routerLink="tmdb" routerLinkActive="active" ariaCurrentWhenActive="page">Synopsis français</a></li>
        <li><a routerLink="posters" routerLinkActive="active" ariaCurrentWhenActive="page">Affiches</a></li>
        <li><a routerLink="cast" routerLinkActive="active" ariaCurrentWhenActive="page">Distribution</a></li>
        <li><a routerLink="users" routerLinkActive="active" ariaCurrentWhenActive="page">Utilisateurs</a></li>
      </ul>
    </nav>
    <router-outlet />
  `,
  styles: `
    .tabs ul {
      display: flex; flex-wrap: wrap; gap: var(--space-1);
      margin: 0 0 var(--space-5); padding: 0; list-style: none;
      border-bottom: 1px solid var(--color-border);
    }
    .tabs a {
      display: inline-flex; align-items: center;
      min-height: var(--target-size); padding: 0 var(--space-4);
      color: var(--color-text-muted); text-decoration: none;
      border-radius: var(--radius) var(--radius) 0 0;
    }
    .tabs a:hover { color: var(--color-text); background: var(--color-surface-raised); }
    .tabs a.active { color: var(--color-text); box-shadow: inset 0 -3px 0 var(--color-accent); }
  `,
})
export class AdminPage {}
