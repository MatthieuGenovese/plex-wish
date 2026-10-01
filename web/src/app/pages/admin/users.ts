import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Role, User } from '../../core/api-types';
import { AdminApi, UpdateUser } from '../../core/admin-api';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/errors';
import { closeModal, openModal } from '../../shared/dialog';
import { formatDateTime } from '../../shared/format';
import { loadOn } from '../../shared/load-state';

const MIN_PASSWORD = 10;

/** Utilisateurs : création, activation / désactivation, rôle, réinitialisation du mot de passe. */
@Component({
  selector: 'app-admin-users',
  imports: [ReactiveFormsModule],
  template: `
    <section class="card" aria-labelledby="create-title">
      <h2 id="create-title">Créer un utilisateur</h2>
      <form [formGroup]="create" (ngSubmit)="submitCreate()" novalidate>
        <div class="form-row">
          <div class="field">
            <label for="u-name">Nom d’utilisateur</label>
            <input id="u-name" formControlName="username" autocomplete="off" autocapitalize="none" spellcheck="false" />
            <span class="hint">3 à 50 caractères : lettres, chiffres, . _ -</span>
          </div>
          <div class="field">
            <label for="u-email">Email <span class="muted">(facultatif)</span></label>
            <input id="u-email" type="email" formControlName="email" autocomplete="off" />
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <label for="u-password">Mot de passe initial</label>
            <input id="u-password" type="password" formControlName="password" autocomplete="new-password" />
            <span class="hint">{{ minPassword }} caractères minimum. À transmettre à la personne.</span>
          </div>
          <div class="field">
            <label for="u-role">Rôle</label>
            <select id="u-role" formControlName="role">
              <option value="USER">Utilisateur</option>
              <option value="ADMIN">Administrateur</option>
            </select>
          </div>
        </div>
        <button type="submit" class="btn-primary" [disabled]="busy()">Créer</button>
      </form>
    </section>

    <div aria-live="polite">
      @if (message(); as m) {
        <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
      }
    </div>

    <section aria-labelledby="users-title">
      <h2 id="users-title">Utilisateurs</h2>
      @let l = users();
      @if (l.error) {
        <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
      } @else if (l.data; as list) {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Nom</th><th scope="col">Email</th><th scope="col">Rôle</th>
                <th scope="col">État</th><th scope="col">Créé le</th><th scope="col"><span class="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (u of list; track u.id) {
                @let self = u.id === auth.user()?.id;
                <tr>
                  <th scope="row">{{ u.username }} @if (self) { <span class="badge">vous</span> }</th>
                  <td>{{ u.email ?? '—' }}</td>
                  <td>
                    <label class="visually-hidden" [for]="'role-' + u.id">Rôle de {{ u.username }}</label>
                    <select [id]="'role-' + u.id" class="compact" [value]="u.role" [disabled]="self || busy()"
                            (change)="changeRole(u, $any($event.target).value)">
                      <option value="USER">Utilisateur</option>
                      <option value="ADMIN">Administrateur</option>
                    </select>
                  </td>
                  <td>
                    <span class="badge" [class.badge-success]="u.enabled" [class.badge-danger]="!u.enabled">{{ u.enabled ? 'Actif' : 'Désactivé' }}</span>
                  </td>
                  <td>{{ date(u.createdAt) }}</td>
                  <td class="actions">
                    <button type="button" class="btn-small" [disabled]="self || busy()" (click)="update(u, { enabled: !u.enabled })"
                            [attr.aria-label]="(u.enabled ? 'Désactiver ' : 'Activer ') + u.username">
                      {{ u.enabled ? 'Désactiver' : 'Activer' }}
                    </button>
                    <button type="button" class="btn-small" [disabled]="busy()" (click)="askPassword(u)"
                            [attr.aria-label]="'Réinitialiser le mot de passe de ' + u.username">Nouveau mot de passe</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="hint">Vous ne pouvez ni vous désactiver ni retirer votre propre rôle d’administrateur. Désactiver un compte ou changer son mot de passe le déconnecte de tous ses appareils.</p>
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
    </section>

    <dialog #passwordDialog aria-labelledby="pw-title">
      <form (ngSubmit)="submitPassword()" novalidate>
        <h2 id="pw-title">Nouveau mot de passe pour {{ target()?.username }}</h2>
        <div class="field">
          <label for="pw-new">Mot de passe</label>
          <input id="pw-new" type="password" [formControl]="newPassword" autocomplete="new-password" />
          <span class="hint">{{ minPassword }} caractères minimum.</span>
        </div>
        @if (dialogError(); as e) { <div class="alert alert-error" role="alert"><p>{{ e }}</p></div> }
        <div class="dialog-actions">
          <button type="button" (click)="closePassword()">Annuler</button>
          <button type="submit" class="btn-primary" [disabled]="busy()">Enregistrer</button>
        </div>
      </form>
    </dialog>
  `,
  styles: `
    select.compact { min-height: 2.5rem; width: auto; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
  `,
})
export class UsersPage {
  private readonly api = inject(AdminApi);
  protected readonly auth = inject(AuthService);
  private readonly passwordDialog = viewChild.required<ElementRef<HTMLDialogElement>>('passwordDialog');

  protected readonly minPassword = MIN_PASSWORD;
  protected readonly date = formatDateTime;
  protected readonly users = loadOn(signal(null), () => this.api.users());
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly target = signal<User | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly newPassword = new FormControl('', { nonNullable: true });

  protected readonly create = new FormGroup({
    username: new FormControl('', { nonNullable: true }),
    email: new FormControl('', { nonNullable: true }),
    password: new FormControl('', { nonNullable: true }),
    role: new FormControl<Role>('USER', { nonNullable: true }),
  });

  submitCreate(): void {
    const v = this.create.getRawValue();
    if (!v.username.trim() || v.password.length < MIN_PASSWORD) {
      this.message.set({ text: `Indiquez un nom d’utilisateur et un mot de passe d’au moins ${MIN_PASSWORD} caractères.`, error: true });
      return;
    }
    this.busy.set(true);
    this.api.createUser({ username: v.username.trim(), email: v.email.trim() || null, password: v.password, role: v.role }).subscribe({
      next: (u) => {
        this.done(`Utilisateur ${u.username} créé.`);
        this.create.reset();
      },
      error: (err: unknown) => this.failed(err),
    });
  }

  changeRole(u: User, role: Role): void {
    this.update(u, { role });
  }

  update(u: User, changes: UpdateUser): void {
    this.busy.set(true);
    this.api.updateUser(u.id, changes).subscribe({
      next: (updated) => {
        const what = changes.enabled !== undefined
          ? (updated.enabled ? 'activé' : 'désactivé')
          : `maintenant ${updated.role === 'ADMIN' ? 'administrateur' : 'utilisateur'}`;
        this.done(`${updated.username} : ${what}.`);
      },
      error: (err: unknown) => {
        this.failed(err);
        this.users.reload(); // remet le <select> à la vraie valeur
      },
    });
  }

  askPassword(u: User): void {
    this.target.set(u);
    this.dialogError.set(null);
    this.newPassword.reset();
    openModal(this.passwordDialog().nativeElement);
  }

  closePassword(): void {
    closeModal(this.passwordDialog().nativeElement);
  }

  submitPassword(): void {
    const u = this.target();
    const password = this.newPassword.value;
    if (!u) return;
    if (password.length < MIN_PASSWORD) {
      this.dialogError.set(`${MIN_PASSWORD} caractères minimum.`);
      return;
    }
    this.busy.set(true);
    this.api.updateUser(u.id, { password }).subscribe({
      next: () => {
        this.closePassword();
        this.done(`Mot de passe de ${u.username} changé. Ses sessions ouvertes sont fermées.`);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.dialogError.set(errorMessage(err));
      },
    });
  }

  private done(text: string): void {
    this.busy.set(false);
    this.message.set({ text, error: false });
    this.users.reload();
  }

  private failed(err: unknown): void {
    this.busy.set(false);
    this.message.set({ text: errorMessage(err), error: true });
  }
}
