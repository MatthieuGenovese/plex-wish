import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, safeReturnUrl } from '../../core/auth.service';
import { errorCode, errorMessage } from '../../core/errors';
import { APP_NAME } from '../../core/app-name';
import { Icon } from '../../shared/icon';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, Icon],
  template: `
    <section class="login" aria-labelledby="login-title">
      <div class="login-brand" aria-hidden="true"><span class="logo"><app-icon name="play_arrow_fill" /></span>{{ appName }}</div>
      <h1 id="login-title">Connexion</h1>
      @if (error(); as e) {
        <div class="alert" [class.alert-error]="!locked()" [class.alert-warning]="locked()" role="alert">
          <p>{{ e }}</p>
        </div>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="login">Identifiant ou e-mail</label>
          <input id="login" formControlName="login" autocomplete="username" autocapitalize="none" spellcheck="false" />
        </div>
        <div class="field">
          <label for="password">Mot de passe</label>
          <input id="password" [type]="showPassword() ? 'text' : 'password'" formControlName="password" autocomplete="current-password" />
        </div>
        <label class="check">
          <input type="checkbox" [checked]="showPassword()" (change)="showPassword.set(!showPassword())" />Afficher le mot de passe
        </label>
        <button type="submit" class="btn-primary btn-lg submit" [disabled]="pending()">
          {{ pending() ? 'Connexion…' : 'Se connecter' }}
        </button>
      </form>
      <p class="hint login-help">Mot de passe oublié ? Demandez à l’administrateur de le réinitialiser.</p>
    </section>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Query param ?returnUrl= (withComponentInputBinding). */
  readonly returnUrl = input<string>();

  protected readonly form = inject(FormBuilder).nonNullable.group({
    login: ['', Validators.required],
    password: ['', Validators.required],
  });
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly locked = signal(false);
  protected readonly showPassword = signal(false);
  protected readonly appName = APP_NAME;

  submit(): void {
    if (this.form.invalid) {
      this.error.set('Renseignez votre identifiant et votre mot de passe.');
      this.locked.set(false);
      return;
    }
    const { login, password } = this.form.getRawValue();
    this.pending.set(true);
    this.error.set(null);
    this.auth.login(login.trim(), password).subscribe({
      next: () => this.router.navigateByUrl(safeReturnUrl(this.returnUrl())),
      error: (err: unknown) => {
        this.pending.set(false);
        this.form.controls.password.reset();
        const code = errorCode(err);
        this.locked.set(code === 'TOO_MANY_ATTEMPTS');
        this.error.set(loginErrorMessage(code, err));
      },
    });
  }
}

function loginErrorMessage(code: string | null, err: unknown): string {
  switch (code) {
    case 'INVALID_CREDENTIALS':
      return 'Identifiant ou mot de passe incorrect (ou compte désactivé).';
    case 'TOO_MANY_ATTEMPTS':
      // Le message du serveur donne le délai : « Réessayez dans N minutes. »
      return errorMessage(err) + ' Par sécurité, les connexions depuis votre appareil sont temporairement bloquées après plusieurs échecs.';
    case 'ORIGIN_NOT_ALLOWED':
      return 'Connexion refusée : l’adresse de ce site ne correspond pas à PUBLIC_URL dans la configuration du serveur. Prévenez l’administrateur.';
    default:
      return errorMessage(err, 'Connexion impossible.');
  }
}
