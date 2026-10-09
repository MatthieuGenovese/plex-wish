import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InvitationLink, Role, User } from '../../core/api-types';
import { AdminApi, UpdateUser } from '../../core/admin-api';
import { AuthService } from '../../core/auth.service';
import { errorMessage } from '../../core/errors';
import { closeModal, openModal } from '../../shared/dialog';
import { formatDateTime } from '../../shared/format';
import { loadOn } from '../../shared/load-state';

/**
 * Utilisateurs : création par invitation (D1.4 : la personne choisit elle-même son mot de passe par un lien à usage
 * unique, valable 72 h), activation / désactivation, rôle, lien de réinitialisation d'un mot de passe oublié.
 */
@Component({
  selector: 'app-admin-users',
  imports: [ReactiveFormsModule],
  template: `
    <section class="card" aria-labelledby="create-title">
      <h2 id="create-title">Créer un utilisateur</h2>
      <form [formGroup]="create" (ngSubmit)="submitCreate()" novalidate>
        <div class="form-row form-top">
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
        <div class="form-row form-top">
          <div class="field">
            <label for="u-role">Rôle</label>
            <select id="u-role" formControlName="role">
              <option value="USER">Utilisateur</option>
              <option value="ADMIN">Administrateur</option>
            </select>
          </div>
        </div>
        <p class="hint">Vous obtiendrez un lien d’invitation à envoyer à la personne : elle y choisira elle-même son mot de passe.</p>
        <button type="submit" class="btn-primary" [disabled]="busy()">Créer et obtenir le lien</button>
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
          <table class="wide">
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
                    @if (!u.enabled) {
                      <span class="badge badge-danger">Désactivé</span>
                    } @else if (u.passwordSet === false) {
                      <span class="badge badge-warning">Invité</span>
                    } @else {
                      <span class="badge badge-success">Actif</span>
                    }
                    @if (u.invitationExpiresAt) { <span class="hint pending">Lien en attente, valable jusqu’au {{ date(u.invitationExpiresAt) }}</span> }
                  </td>
                  <td>{{ date(u.createdAt) }}</td>
                  <td class="actions">
                    <button type="button" class="btn-small" [disabled]="self || busy()" (click)="update(u, { enabled: !u.enabled })"
                            [attr.aria-label]="(u.enabled ? 'Désactiver ' : 'Activer ') + u.username">
                      {{ u.enabled ? 'Désactiver' : 'Activer' }}
                    </button>
                    <button type="button" class="btn-small" [disabled]="busy() || !u.enabled" (click)="newLink(u)"
                            [attr.aria-label]="(u.passwordSet === false ? 'Nouveau lien d’invitation pour ' : 'Lien de nouveau mot de passe pour ') + u.username">
                      {{ u.passwordSet === false ? 'Nouveau lien d’invitation' : 'Lien de nouveau mot de passe' }}
                    </button>
                    @if (u.invitationExpiresAt) {
                      <button type="button" class="btn-small" [disabled]="busy()" (click)="revoke(u)"
                              [attr.aria-label]="'Révoquer le lien de ' + u.username">Révoquer le lien</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="hint">Vous ne pouvez ni vous désactiver ni retirer votre propre rôle d’administrateur. Désactiver un compte le déconnecte de tous ses appareils et annule son lien en attente. Un nouveau mot de passe choisi par lien ferme aussi toutes ses sessions.</p>
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
    </section>

    <dialog #linkDialog aria-labelledby="link-title">
      @if (link(); as l) {
        <h2 id="link-title">{{ l.purpose === 'INVITE' ? 'Lien d’invitation' : 'Lien de nouveau mot de passe' }} pour {{ target()?.username }}</h2>
        <p>Envoyez ce lien à la personne, par un message privé. Il ne sert qu’une fois et reste valable jusqu’au {{ date(l.expiresAt) }}.
          Il ne sera plus affiché ensuite : en cas de perte, créez-en un nouveau (l’ancien cessera de fonctionner).</p>
        <div class="field">
          <label for="link-url">Lien</label>
          <input id="link-url" readonly [value]="l.url" (focus)="$any($event.target).select()" />
        </div>
        @if (copied()) { <p class="hint" role="status">Lien copié.</p> }
        <div class="dialog-actions">
          <button type="button" (click)="copy(l.url)">Copier le lien</button>
          <button type="button" class="btn-primary" (click)="closeLink()">Fermer</button>
        </div>
      }
    </dialog>
  `,
  styles: `
    select.compact { min-height: 2.5rem; width: auto; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    th[scope='row'] { background: none; font-weight: var(--font-weight-medium); }
    .pending { display: block; margin-top: var(--space-1, 0.25rem); }
    #link-url { font-family: monospace; }
  `,
})
export class UsersPage {
  private readonly api = inject(AdminApi);
  protected readonly auth = inject(AuthService);
  private readonly linkDialog = viewChild.required<ElementRef<HTMLDialogElement>>('linkDialog');

  protected readonly date = formatDateTime;
  protected readonly users = loadOn(signal(null), () => this.api.users());
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly target = signal<User | null>(null);
  protected readonly link = signal<InvitationLink | null>(null);
  protected readonly copied = signal(false);

  protected readonly create = new FormGroup({
    username: new FormControl('', { nonNullable: true }),
    email: new FormControl('', { nonNullable: true }),
    role: new FormControl<Role>('USER', { nonNullable: true }),
  });

  submitCreate(): void {
    const v = this.create.getRawValue();
    if (!v.username.trim()) {
      this.message.set({ text: 'Indiquez un nom d’utilisateur.', error: true });
      return;
    }
    this.busy.set(true);
    this.api.createUser({ username: v.username.trim(), email: v.email.trim() || null, role: v.role }).subscribe({
      next: (u) => {
        this.done(`Utilisateur ${u.username} créé.`);
        this.create.reset();
        if (u.invitation) {
          this.showLink(u, u.invitation);
        }
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

  newLink(u: User): void {
    this.busy.set(true);
    this.api.newInvitation(u.id).subscribe({
      next: (l) => {
        this.done(l.purpose === 'INVITE' ? `Nouveau lien d’invitation pour ${u.username}.` : `Lien de nouveau mot de passe pour ${u.username}.`);
        this.showLink(u, l);
      },
      error: (err: unknown) => this.failed(err),
    });
  }

  revoke(u: User): void {
    this.busy.set(true);
    this.api.revokeInvitation(u.id).subscribe({
      next: () => this.done(`Lien de ${u.username} révoqué.`),
      error: (err: unknown) => this.failed(err),
    });
  }

  copy(url: string): void {
    navigator.clipboard?.writeText(url).then(() => this.copied.set(true), () => this.copied.set(false));
  }

  closeLink(): void {
    closeModal(this.linkDialog().nativeElement);
    this.link.set(null); // le lien n'est plus gardé en mémoire
  }

  private showLink(u: User, l: InvitationLink): void {
    this.target.set(u);
    this.link.set(l);
    this.copied.set(false);
    openModal(this.linkDialog().nativeElement);
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
