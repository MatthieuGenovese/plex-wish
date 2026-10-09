import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { APP_NAME } from '../../core/app-name';
import { errorMessage } from '../../core/errors';
import { Icon } from '../../shared/icon';

const MIN_PASSWORD = 10;

interface Preview {
  username: string;
  purpose: 'INVITE' | 'RESET';
  expiresAt: string;
}

/**
 * Lien d'invitation ou de nouveau mot de passe (D1.4) : /invitation#<jeton>. Le jeton est après le « # » : le
 * navigateur ne l'envoie jamais dans l'adresse. Il est lu une fois, retiré de la barre d'adresse et de l'historique,
 * puis envoyé dans le corps des requêtes.
 */
@Component({
  selector: 'app-invitation',
  imports: [ReactiveFormsModule, RouterLink, Icon],
  template: `
    <section class="login" aria-labelledby="inv-title">
      <div class="login-brand" aria-hidden="true"><span class="logo"><app-icon name="play_arrow_fill" /></span>{{ appName }}</div>
      @if (done(); as name) {
        <h1 id="inv-title">Mot de passe enregistré</h1>
        <p role="status">Vous pouvez maintenant vous connecter avec l’identifiant <strong>{{ name }}</strong> et ce mot de passe.</p>
        <a class="btn btn-primary btn-lg" routerLink="/login">Se connecter</a>
      } @else if (preview(); as p) {
        <h1 id="inv-title">{{ p.purpose === 'INVITE' ? 'Bienvenue, ' + p.username : 'Nouveau mot de passe' }}</h1>
        <p>{{ p.purpose === 'INVITE' ? 'Choisissez votre mot de passe pour activer votre compte.' : 'Choisissez un nouveau mot de passe pour le compte ' + p.username + '. Vos appareils déjà connectés devront se reconnecter.' }}</p>
        @if (error(); as e) { <div class="alert alert-error" role="alert"><p>{{ e }}</p></div> }
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <input type="text" name="username" autocomplete="username" [value]="p.username" hidden />
          <div class="field">
            <label for="inv-pass">Mot de passe</label>
            <input id="inv-pass" [type]="show() ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
            <span class="hint">{{ minPassword }} caractères minimum.</span>
          </div>
          <div class="field">
            <label for="inv-pass2">Mot de passe, encore une fois</label>
            <input id="inv-pass2" [type]="show() ? 'text' : 'password'" formControlName="confirm" autocomplete="new-password" />
          </div>
          <label class="check"><input type="checkbox" [checked]="show()" (change)="show.set(!show())" />Afficher le mot de passe</label>
          <button type="submit" class="btn-primary btn-lg submit" [disabled]="busy()">Enregistrer mon mot de passe</button>
        </form>
      } @else if (error(); as e) {
        <h1 id="inv-title">Lien non valable</h1>
        <div class="alert alert-error" role="alert"><p>{{ e }}</p></div>
        <a routerLink="/login">Aller à la connexion</a>
      } @else {
        <h1 id="inv-title">Invitation</h1>
        <p class="muted" role="status">Vérification du lien…</p>
      }
    </section>
  `,
})
export class InvitationPage {
  private readonly http = inject(HttpClient);
  protected readonly appName = APP_NAME;
  protected readonly minPassword = MIN_PASSWORD;
  protected readonly preview = signal<Preview | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly show = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({ password: [''], confirm: [''] });
  private readonly token: string;

  constructor() {
    this.token = decodeURIComponent(location.hash.replace(/^#/, ''));
    // Le jeton quitte la barre d'adresse et l'historique du navigateur.
    history.replaceState(history.state, '', location.pathname);
    if (!this.token) {
      this.error.set('Ce lien est incomplet. Copiez-le en entier depuis le message reçu.');
      return;
    }
    this.http.post<Preview>('/api/invitation/check', { token: this.token }).subscribe({
      next: (p) => this.preview.set(p),
      error: (err: unknown) => this.error.set(errorMessage(err)),
    });
  }

  submit(): void {
    const v = this.form.getRawValue();
    if (v.password.length < MIN_PASSWORD) {
      this.error.set(`Le mot de passe doit faire au moins ${MIN_PASSWORD} caractères.`);
      return;
    }
    if (v.password !== v.confirm) {
      this.error.set('Les deux mots de passe sont différents.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.http.post<{ username: string }>('/api/invitation/accept', { token: this.token, password: v.password }).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.form.reset();
        this.done.set(r.username);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
