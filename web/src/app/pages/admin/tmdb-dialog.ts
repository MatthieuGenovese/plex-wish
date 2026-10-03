import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { TmdbConflict, TmdbEntry, TmdbSheet, TmdbType } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorCode, errorMessage } from '../../core/errors';
import { closeModal, openModal } from '../../shared/dialog';

type Choice = { kind: 'candidate'; type: TmdbType; id: number } | { kind: 'other' } | { kind: 'none' } | null;

/**
 * Lit « tv/209867 », « movie 372058 » ou l'adresse d'une fiche (themoviedb.org/tv/209867-frieren).
 * Un nombre seul est une série (cas le plus courant pour un animé).
 */
export function parseTmdbRef(text: string): { type: TmdbType; tmdbId: number } | null {
  const t = text.trim();
  const m = /(?:^|\/|\s)(tv|movie)[/\s]+(\d{1,9})(?:\D|$)/i.exec(t) ?? (/^(\d{1,9})$/.exec(t) ? [t, 'tv', t] : null);
  return m ? { type: m[1].toLowerCase() as TmdbType, tmdbId: Number(m[2]) } : null;
}

/**
 * Correction TMDB d'un animé : choisir un candidat, donner une fiche par son identifiant ou son adresse (avec
 * prévisualisation), ou « aucune fiche TMDB » (synopsis anglais). Verrouillé ; 409 + confirmation s'il remplace
 * une fiche.
 */
@Component({
  selector: 'app-tmdb-dialog',
  template: `
    <dialog #dialog aria-labelledby="td-title">
      @if (entry(); as e) {
        <h2 id="td-title">Fiche TMDB de « {{ e.title }} »</h2>
        @if (conflict(); as c) {
          <div class="conflict" role="alert">
            <h3>Remplacer la fiche actuelle ?</h3>
            <p><strong>Actuelle :</strong> {{ label(c.current) }}</p>
            <p><strong>Nouvelle :</strong> {{ c.proposed ? label(c.proposed) : 'aucune fiche TMDB (synopsis anglais)' }}</p>
          </div>
          @if (error(); as err) { <div class="alert alert-error" role="alert"><p>{{ err }}</p></div> }
          <div class="dialog-actions">
            <button type="button" (click)="conflict.set(null)">Retour</button>
            <button type="button" class="btn-danger" (click)="apply(true)" [disabled]="pending()">Remplacer</button>
          </div>
        } @else {
          @if (e.locked) {
            <p class="alert">Correction manuelle verrouillée : l’appariement automatique n’y touche pas.
              <button type="button" class="btn-link" (click)="unlock()" [disabled]="pending()">Revenir à l’appariement automatique</button></p>
          }
          <fieldset>
            <legend class="label">Choisir la fiche TMDB</legend>
            <ul class="choices">
              @for (cand of e.candidates; track cand.type + cand.tmdbId) {
                <li>
                  <label class="choice">
                    <input type="radio" name="td-choice" [checked]="isCandidate(cand.type, cand.tmdbId)"
                           (change)="choose({ kind: 'candidate', type: cand.type, id: cand.tmdbId })" />
                    <span>
                      <strong>{{ cand.name ?? cand.originalName }}</strong>
                      @if (cand.type === e.tmdbType && cand.tmdbId === e.tmdbId) { <span class="badge badge-success">actuelle</span> }<br />
                      <span class="muted">{{ cand.originalName }} · {{ cand.year ?? '?' }} · {{ cand.type === 'tv' ? 'série' : 'film' }}
                        @if (!cand.animation) { · <span class="badge badge-warning">pas « Animation »</span> }
                        @if (!cand.hasFrenchOverview) { · sans synopsis français }
                        @if (cand.score !== undefined) { · {{ (cand.score * 100).toFixed(0) }} % }
                        · <a [href]="cand.url" target="_blank" rel="noopener noreferrer">voir</a>
                      </span>
                    </span>
                  </label>
                </li>
              }
              <li>
                <label class="choice">
                  <input type="radio" name="td-choice" [checked]="choice()?.kind === 'other'" (change)="choose({ kind: 'other' })" />
                  <span>Autre fiche, par son adresse ou son identifiant TMDB</span>
                </label>
                @if (choice()?.kind === 'other') {
                  <div class="other">
                    <div class="field">
                      <label for="td-ref">Adresse ou identifiant</label>
                      <input id="td-ref" autocomplete="off" [value]="otherRef()" (input)="otherRef.set($any($event.target).value); preview.set(null)" />
                      <span class="hint">Ex. themoviedb.org/<strong>tv/209867</strong>-frieren, ou <strong>movie/372058</strong> pour un film.</span>
                    </div>
                    <button type="button" class="btn-small" (click)="loadPreview()" [disabled]="pending() || !ref()">Prévisualiser</button>
                    @if (preview(); as p) {
                      <div class="preview" data-testid="td-preview">
                        <strong>{{ label(p) }}</strong>
                        @if (!p.animation) { <span class="badge badge-warning">pas « Animation »</span> }
                        <p class="muted overview">{{ p.overview ?? 'Pas de synopsis en français sur cette fiche.' }}</p>
                      </div>
                    }
                  </div>
                }
              </li>
              <li>
                <label class="choice">
                  <input type="radio" name="td-choice" [checked]="choice()?.kind === 'none'" (change)="choose({ kind: 'none' })" />
                  <span>Aucune fiche TMDB <span class="muted">(synopsis anglais d’AniList)</span></span>
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
    .choice { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) var(--space-3); border: 1px solid var(--color-border); border-radius: var(--radius); cursor: pointer; }
    .other { padding: var(--space-3) 0 0 var(--space-6); }
    .preview { margin-top: var(--space-3); }
    .overview { font-size: var(--font-size-sm); max-height: 8rem; overflow-y: auto; margin: var(--space-2) 0 0; }
    .conflict { padding: var(--space-3) var(--space-4); border-left: 4px solid var(--color-warning); background: var(--color-warning-bg); border-radius: var(--radius); }
    .conflict h3 { font-size: var(--font-size-md); }
  `,
})
export class TmdbDialog {
  private readonly api = inject(AdminApi);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly changed = output<string>();

  protected readonly entry = signal<TmdbEntry | null>(null);
  protected readonly choice = signal<Choice>(null);
  protected readonly otherRef = signal('');
  protected readonly preview = signal<TmdbSheet | null>(null);
  protected readonly conflict = signal<TmdbConflict | null>(null);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  open(e: TmdbEntry): void {
    this.entry.set(e);
    this.choice.set(e.tmdbType && e.tmdbId ? { kind: 'candidate', type: e.tmdbType, id: e.tmdbId } : null);
    this.otherRef.set('');
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

  isCandidate(type: TmdbType, id: number): boolean {
    const c = this.choice();
    return c?.kind === 'candidate' && c.type === type && c.id === id;
  }

  ref(): { type: TmdbType; tmdbId: number } | null {
    return parseTmdbRef(this.otherRef());
  }

  ready(): boolean {
    const c = this.choice();
    return c !== null && (c.kind !== 'other' || this.ref() !== null);
  }

  label(s: TmdbSheet | null): string {
    if (!s) return '—';
    const name = s.name ?? s.originalName ?? '?';
    const original = s.originalName && s.originalName !== name ? ` (${s.originalName})` : '';
    return `${name}${original} · ${s.year ?? '?'} · ${s.type === 'tv' ? 'série' : 'film'} ${s.tmdbId}`;
  }

  loadPreview(): void {
    const e = this.entry();
    const r = this.ref();
    if (!e || !r) return;
    this.pending.set(true);
    this.error.set(null);
    this.api.tmdbPreview(e.animeId, r.type, r.tmdbId).subscribe({
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
    const sheet = c.kind === 'candidate' ? { type: c.type, tmdbId: c.id } : c.kind === 'other' ? this.ref() : null;
    this.pending.set(true);
    this.error.set(null);
    this.api.setTmdb(e.animeId, sheet, replace).subscribe({
      next: (updated) => {
        this.pending.set(false);
        this.close();
        this.changed.emit(updated.tmdbId
          ? `« ${e.title} » : fiche TMDB ${updated.tmdbType}/${updated.tmdbId} appliquée et verrouillée`
            + (updated.hasFrenchSynopsis ? '.' : ' (elle n’a pas de synopsis français).')
          : `« ${e.title} » : aucune fiche TMDB, choix verrouillé.`);
      },
      error: (err: unknown) => {
        this.pending.set(false);
        if (!replace && errorCode(err) === 'TMDB_CONFLICT') {
          this.conflict.set((err as HttpErrorResponse).error as TmdbConflict);
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
    this.api.unlockTmdb(e.animeId).subscribe({
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
