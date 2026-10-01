import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, safeReturnUrl } from '../../core/auth.service';
import { errorCode, errorMessage } from '../../core/errors';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  template: `
    <section class="card login" aria-labelledby="login-title">
      <h1 id="login-title">Connexion</h1>
      @if (error(); as e) {
        <div class="alert" [class.alert-error]="!locked()" [class.alert-warning]="locked()" role="alert">
          <p>{{ e }}</p>
        </div>
      }
      <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="login">Identifiant ou email</label>
          <input id="login" formControlName="login" autocomplete="username" autocapitalize="none" spellcheck="false" />
        </div>
        <div class="field">
          <label for="password">Mot de passe</label>
          <input id="password" type="password" formControlName="password" autocomplete="current-password" />
        </div>
        <button type="submit" class="btn-primary submit" [disabled]="pending()">
          {{ pending() ? 'Connexion…' : 'Se connecter' }}
        </button>
      </form>
    </section>
  `,
  styles: `
    .login { max-width: 26rem; margin: var(--space-6) auto; }
    .submit { width: 100%; }
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
