import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { errorCode, errorMessage } from '../../core/errors';
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
  imports: [RouterLink, Icon, ThemeSwitch, ReactiveFormsModule],
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

        <section class="panel" aria-labelledby="password-title">
          <h2 id="password-title">Mot de passe</h2>
          <p class="hint">Les autres appareils et navigateurs connectés à votre compte seront déconnectés.</p>
          @if (passwordMessage(); as m) {
            <div class="alert" [class.alert-success]="m.ok" [class.alert-error]="!m.ok" [attr.role]="m.ok ? 'status' : 'alert'"><p>{{ m.text }}</p></div>
          }
          <form [formGroup]="passwordForm" (ngSubmit)="changePassword()" novalidate class="password-form">
            <div class="field">
              <label for="current-password">Mot de passe actuel</label>
              <input id="current-password" [type]="show() ? 'text' : 'password'" formControlName="current" autocomplete="current-password" />
            </div>
            <div class="field">
              <label for="new-password">Nouveau mot de passe</label>
              <input id="new-password" [type]="show() ? 'text' : 'password'" formControlName="next" autocomplete="new-password"
                     aria-describedby="new-password-hint" />
              <span class="hint" id="new-password-hint">10 caractères au moins.</span>
            </div>
            <div class="field">
              <label for="confirm-password">Confirmer le nouveau mot de passe</label>
              <input id="confirm-password" [type]="show() ? 'text' : 'password'" formControlName="confirm" autocomplete="new-password" />
            </div>
            <label class="check"><input type="checkbox" [checked]="show()" (change)="show.set(!show())" />Afficher les mots de passe</label>
            <button type="submit" class="btn-primary" [disabled]="pending()">{{ pending() ? 'Enregistrement…' : 'Changer le mot de passe' }}</button>
          </form>
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

  protected readonly passwordForm = inject(FormBuilder).nonNullable.group({ current: '', next: '', confirm: '' });
  protected readonly show = signal(false);
  protected readonly pending = signal(false);
  protected readonly passwordMessage = signal<{ ok: boolean; text: string } | null>(null);

  /** Changement de mot de passe (S4) : vérifications simples ici, règles complètes côté serveur. */
  changePassword(): void {
    const { current, next, confirm } = this.passwordForm.getRawValue();
    const fail = (text: string) => this.passwordMessage.set({ ok: false, text });
    if (!current || !next) return fail('Renseignez le mot de passe actuel et le nouveau.');
    if (next.length < 10) return fail('Le nouveau mot de passe doit faire au moins 10 caractères.');
    if (next !== confirm) return fail('Les deux nouveaux mots de passe ne sont pas identiques.');
    this.pending.set(true);
    this.passwordMessage.set(null);
    this.auth.changePassword(current, next).subscribe({
      next: (r) => {
        this.pending.set(false);
        this.passwordForm.reset();
        const n = r.closedSessions;
        this.passwordMessage.set({ ok: true, text: 'Mot de passe changé.' + (n === 0 ? '' : n === 1
          ? ' Une autre session a été fermée.' : ` ${n} autres sessions ont été fermées.`) });
      },
      error: (err: unknown) => {
        this.pending.set(false);
        this.passwordForm.controls.current.reset();
        fail(errorCode(err) === 'WRONG_PASSWORD' ? 'Le mot de passe actuel est incorrect.' : errorMessage(err, 'Le mot de passe n’a pas pu être changé.'));
      },
    });
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/login'));
  }
}
