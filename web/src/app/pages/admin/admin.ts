import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

/** Sections de l'administration (chemin relatif → libellé). */
export const ADMIN_SECTIONS: { path: string; label: string }[] = [
  { path: 'scan', label: 'Scan' },
  { path: 'report', label: 'Rapport' },
  { path: 'corrections', label: 'Corrections' },
  { path: 'metadata', label: 'Métadonnées' },
  { path: 'tmdb', label: 'Synopsis français' },
  { path: 'posters', label: 'Affiches' },
  { path: 'cast', label: 'Distribution' },
  { path: 'media', label: 'Médias' },
  { path: 'users', label: 'Utilisateurs' },
  { path: 'settings', label: 'Réglages' },
];

/**
 * Administration : onglets sur grand écran, liste déroulante sur téléphone (neuf onglets prenaient la moitié
 * de l'écran). Les deux sont dans le DOM, le CSS n'en montre qu'un.
 */
@Component({
  selector: 'app-admin',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="admin-head">
      <h1>Administration</h1>
      <div class="admin-picker">
        <label for="admin-section">Section</label>
        <select id="admin-section" (change)="go($event)">
          @for (s of sections; track s.path) {
            <option [value]="s.path" [selected]="current() === s.path">{{ s.label }}</option>
          }
        </select>
      </div>
    </div>
    <nav aria-label="Administration" class="tabs admin-tabs">
      <ul>
        @for (s of sections; track s.path) {
          <li><a [routerLink]="s.path" routerLinkActive="active" ariaCurrentWhenActive="page">{{ s.label }}</a></li>
        }
      </ul>
    </nav>
    <router-outlet />
  `,
})
export class AdminPage {
  private readonly router = inject(Router);
  protected readonly sections = ADMIN_SECTIONS;
  protected readonly current = toSignal(
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map(() => this.section())),
    { initialValue: this.section() },
  );

  private section(): string {
    return this.router.url.split(/[?#]/)[0].split('/')[2] ?? 'scan';
  }

  protected go(event: Event): void {
    this.router.navigate(['/admin', (event.target as HTMLSelectElement).value]);
  }
}
