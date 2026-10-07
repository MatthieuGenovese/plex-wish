import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { Icon } from '../../shared/icon';
import { ThemeSwitch } from '../../shared/theme-switch';

/**
 * Mon compte : identité, thème de cet appareil, liens (administration, à propos), déconnexion. Sur téléphone,
 * c'est l'onglet « Compte » de la barre du bas ; sur ordinateur, on y arrive par le menu Compte.
 */
@Component({
  selector: 'app-account',
  imports: [RouterLink, Icon, ThemeSwitch],
  template: `
    @if (auth.user(); as user) {
      <div class="account-page">
        <header class="account-head">
          <span class="avatar-lg" aria-hidden="true">{{ user.username[0].toUpperCase() }}</span>
          <div>
            <h1>{{ user.username }}</h1>
            <p class="muted">{{ user.role === 'ADMIN' ? 'Administrateur' : 'Utilisateur' }}</p>
          </div>
        </header>

        <section class="panel" aria-labelledby="theme-title">
          <h2 id="theme-title">Thème</h2>
          <p class="hint">Enregistré sur cet appareil. « Système » suit le réglage de l’appareil.</p>
          <app-theme-switch />
        </section>

        <nav class="panel link-list" aria-label="Autres pages">
          @if (auth.isAdmin()) {
            <a routerLink="/admin"><app-icon name="admin_panel_settings" /><span>Administration</span><app-icon name="chevron_right" class="end" /></a>
          }
          <a routerLink="/a-propos"><app-icon name="info" /><span>À propos</span><app-icon name="chevron_right" class="end" /></a>
          <button type="button" (click)="logout()"><app-icon name="logout" /><span>Se déconnecter</span></button>
        </nav>
      </div>
    }
  `,
})
export class AccountPage {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }
}
