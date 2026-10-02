import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { MetadataConflict, MetadataEntry, MetadataSheet } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorCode, errorMessage } from '../../core/errors';
import { closeModal, openModal } from '../../shared/dialog';
import { Poster } from '../../shared/poster';

type Choice = { kind: 'candidate'; id: string } | { kind: 'other' } | { kind: 'none' } | null;

/**
 * Correction d'un appariement : choisir un candidat, donner un identifiant AniList (avec prévisualisation)
 * ou « aucune fiche ». La correction est verrouillée. Si elle remplace une fiche existante (ou une fiche déjà
 * utilisée par un autre animé), le serveur répond 409 et on montre l'actuelle et la nouvelle avant de confirmer.
 */
@Component({
  selector: 'app-metadata-dialog',
  imports: [Poster],
  template: `
    <dialog #dialog aria-labelledby="md-title">
      @if (entry(); as e) {
        <h2 id="md-title">Fiche de « {{ e.title }} »</h2>
        @if (conflict(); as c) {
          <div class="conflict" role="alert">
            <h3>{{ c.current ? 'Remplacer la fiche actuelle ?' : 'Utiliser cette fiche ?' }}</h3>
            <div class="compare">
              @if (c.current; as cur) {
                <figure><app-poster [title]="cur.title" [url]="cur.posterUrl" /><figcaption><strong>Actuelle</strong><br />{{ cur.title }} {{ cur.year ?? '' }}</figcaption></figure>
              }
              <figure>
                @if (c.proposed; as p) {
                  <app-poster [title]="p.title" [url]="p.posterUrl" /><figcaption><strong>Nouvelle</strong><br />{{ p.title }} {{ p.year ?? '' }}</figcaption>
                } @else {
                  <app-poster [title]="e.title" /><figcaption><strong>Nouvelle</strong><br />Aucune fiche</figcaption>
                }
              </figure>
            </div>
            @if (c.otherAnime.length > 0) {
              <p>Cette fiche est déjà utilisée par : <strong>{{ names(c.otherAnime) }}</strong>. Les deux animés l’afficheront.</p>
            }
          </div>
          @if (error(); as err) { <div class="alert alert-error" role="alert"><p>{{ err }}</p></div> }
          <div class="dialog-actions">
            <button type="button" (click)="conflict.set(null)">Retour</button>
            <button type="button" class="btn-danger" (click)="apply(true)" [disabled]="pending()">Remplacer</button>
          </div>
        } @else {
          @if (e.locked) {
            <p class="alert">Correction manuelle verrouillée : la récupération automatique n’y touche pas.
              <button type="button" class="btn-link" (click)="unlock()" [disabled]="pending()">Revenir à l’appariement automatique</button></p>
          }
          <fieldset>
            <legend class="label">Choisir la fiche AniList</legend>
            <ul class="choices">
              @for (cand of e.candidates; track cand.providerId) {
                <li>
                  <label class="choice">
                    <input type="radio" name="md-choice" [checked]="isCandidate(cand.providerId)" (change)="choose({ kind: 'candidate', id: cand.providerId })" />
                    <app-poster class="thumb" [title]="cand.title" [url]="cand.posterUrl" />
                    <span>
                      <strong>{{ cand.title }}</strong>
                      @if (cand.providerId === e.providerId) { <span class="badge badge-success">actuelle</span> }<br />
                      <span class="muted">{{ cand.year ?? '?' }} · {{ cand.format ?? '?' }} · {{ cand.episodes ?? '?' }} ép.
                        @if (cand.score !== undefined) { · {{ (cand.score * 100).toFixed(0) }} % }
                        @if (cand.siteUrl) { · <a [href]="cand.siteUrl" target="_blank" rel="noopener noreferrer">AniList {{ cand.providerId }}</a> }
                      </span>
                    </span>
                  </label>
                </li>
              }
              <li>
                <label class="choice">
                  <input type="radio" name="md-choice" [checked]="choice()?.kind === 'other'" (change)="choose({ kind: 'other' })" />
                  <span>Autre fiche, par son identifiant AniList</span>
                </label>
                @if (choice()?.kind === 'other') {
                  <div class="other">
                    <div class="field">
                      <label for="md-id">Identifiant AniList</label>
                      <input id="md-id" inputmode="numeric" autocomplete="off" [value]="otherId()" (input)="otherId.set($any($event.target).value.trim()); preview.set(null)" />
                      <span class="hint">Le nombre dans l’adresse de la fiche : anilist.co/anime/<strong>154587</strong>/…</span>
                    </div>
                    <button type="button" class="btn-small" (click)="loadPreview()" [disabled]="pending() || !validId()">Prévisualiser</button>
                    @if (preview(); as p) {
                      <div class="preview" data-testid="md-preview">
                        <app-poster class="thumb" [title]="p.title" [url]="p.posterUrl" />
                        <span><strong>{{ p.title }}</strong><br /><span class="muted">{{ p.year ?? '?' }} · {{ p.format ?? '?' }} · {{ p.episodes ?? '?' }} ép.</span></span>
                      </div>
                    }
                  </div>
                }
              </li>
              <li>
                <label class="choice">
                  <input type="radio" name="md-choice" [checked]="choice()?.kind === 'none'" (change)="choose({ kind: 'none' })" />
                  <span>Aucune fiche <span class="muted">(pas d’affiche ni de synopsis pour cet animé)</span></span>
                </label>
              </li>
            </ul>
          </fieldset>
          @if (error(); as err) { <div class="alert alert-error" role="alert"><p>{{ err }}</p></div> }
          <div class="dialog-actions">
            <button type="button" (click)="close()">Annuler</button>
            <button type="button" class="btn-primary" (click)="apply(false)" [disabled]="pending() || !ready()">Appliquer</button>
          </div>
        }
      }
    </dialog>
  `,
  styles: `
    dialog { width: min(40rem, calc(100vw - 2 * var(--space-4))); }
    fieldset { border: 0; padding: 0; margin: 0; }
    .choices { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); max-height: 50vh; overflow-y: auto; }
    .choice { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2); border: 1px solid var(--color-border); border-radius: var(--radius); cursor: pointer; }
    .thumb { width: 2.75rem; flex: 0 0 auto; }
    .other { padding: var(--space-3) 0 0 var(--space-6); }
    .preview { display: flex; gap: var(--space-3); align-items: center; margin-top: var(--space-3); }
    .conflict { padding: var(--space-3) var(--space-4); border-left: 4px solid var(--color-warning); background: var(--color-warning-bg); border-radius: var(--radius); }
    .conflict h3 { font-size: var(--font-size-md); }
    .compare { display: grid; grid-template-columns: repeat(2, minmax(0, 9rem)); gap: var(--space-4); }
    figure { margin: 0; }
    figcaption { font-size: var(--font-size-sm); margin-top: var(--space-1); }
  `,
})
export class MetadataDialog {
  private readonly api = inject(AdminApi);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  /** Message de succès, après une modification. */
  readonly changed = output<string>();

  protected readonly entry = signal<MetadataEntry | null>(null);
  protected readonly choice = signal<Choice>(null);
  protected readonly otherId = signal('');
  protected readonly preview = signal<MetadataSheet | null>(null);
  protected readonly conflict = signal<MetadataConflict | null>(null);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  open(e: MetadataEntry): void {
    this.entry.set(e);
    this.choice.set(e.providerId ? { kind: 'candidate', id: e.providerId } : null);
    this.otherId.set('');
    this.preview.set(null);
    this.conflict.set(null);
    this.error.set(null);
    this.pending.set(false);
    queueMicrotask(() => openModal(this.dialog().nativeElement));
  }

  close(): void {
    closeModal(this.dialog().nativeElement);
  }

  choose(c: Choice): void {
    this.choice.set(c);
    this.error.set(null);
  }

  isCandidate(id: string): boolean {
    const c = this.choice();
    return c?.kind === 'candidate' && c.id === id;
  }

  validId(): boolean {
    return /^\d{1,9}$/.test(this.otherId());
  }

  ready(): boolean {
    const c = this.choice();
    return c !== null && (c.kind !== 'other' || this.validId());
  }

  names(list: { title: string }[]): string {
    return list.map((a) => a.title).join(', ');
  }

  loadPreview(): void {
    const e = this.entry();
    if (!e || !this.validId()) return;
    this.pending.set(true);
    this.error.set(null);
    this.api.metadataPreview(e.animeId, this.otherId()).subscribe({
      next: (sheet) => {
        this.pending.set(false);
        this.preview.set(sheet);
      },
      error: (err: unknown) => {
        this.pending.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  /** Sans replace : le serveur demande confirmation (409) si une fiche serait remplacée. */
  apply(replace: boolean): void {
    const e = this.entry();
    const c = this.choice();
    if (!e || !c) return;
    const providerId = c.kind === 'candidate' ? c.id : c.kind === 'other' ? this.otherId() : null;
    this.pending.set(true);
    this.error.set(null);
    this.api.setMetadata(e.animeId, providerId, replace).subscribe({
      next: (updated) => {
        this.pending.set(false);
        this.close();
        this.changed.emit(updated.providerId
          ? `« ${e.title} » : fiche « ${updated.matchedTitle} » appliquée et verrouillée.`
          : `« ${e.title} » : aucune fiche, choix verrouillé.`);
      },
      error: (err: unknown) => {
        this.pending.set(false);
        if (!replace && errorCode(err) === 'METADATA_CONFLICT') {
          this.conflict.set((err as HttpErrorResponse).error as MetadataConflict);
        } else {
          this.error.set(errorMessage(err));
        }
      },
    });
  }

  unlock(): void {
    const e = this.entry();
    if (!e) return;
    this.pending.set(true);
    this.api.unlockMetadata(e.animeId).subscribe({
      next: () => {
        this.pending.set(false);
        this.close();
        this.changed.emit(`« ${e.title} » : retour à l’appariement automatique.`);
      },
      error: (err: unknown) => {
        this.pending.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
