import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Issue, OverrideAction, OverrideRequest } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { LibraryApi } from '../../core/library-api';
import { errorMessage } from '../../core/errors';
import { closeModal, openModal } from '../../shared/dialog';

/**
 * Correction manuelle d'un fichier signalé : épisode (animé, saison, épisode), extra ou ignoré.
 * Le fichier est désigné par son id ; la correction s'applique au prochain scan (§7.7).
 */
@Component({
  selector: 'app-override-dialog',
  imports: [ReactiveFormsModule],
  template: `
    <dialog #dialog aria-labelledby="override-title">
      @if (issue(); as i) {
        <h2 id="override-title">Corriger le fichier n° {{ i.mediaFileId }}</h2>
        <p class="path mono">{{ i.relativePath }}</p>
        <form [formGroup]="form" (ngSubmit)="save()" novalidate>
          <fieldset>
            <legend class="label">Ce fichier est…</legend>
            <label class="choice"><input type="radio" formControlName="action" value="EPISODE" /> un épisode</label>
            <label class="choice"><input type="radio" formControlName="action" value="EXTRA" /> un extra (générique, bonus sans numéro…)</label>
            <label class="choice"><input type="radio" formControlName="action" value="IGNORE" /> à ignorer</label>
          </fieldset>
          @if (form.controls.action.value === 'EPISODE') {
            <div class="field">
              <label for="ov-anime">Animé</label>
              <input id="ov-anime" formControlName="animeTitle" list="ov-anime-titles" autocomplete="off" (input)="titles$.next($any($event.target).value)" />
              <datalist id="ov-anime-titles">
                @for (t of suggestions(); track t) { <option [value]="t"></option> }
              </datalist>
              <span class="hint">Titre exact d’un animé existant, ou un nouveau titre.</span>
            </div>
            <div class="form-row">
              <div class="field">
                <label for="ov-season">Saison</label>
                <input id="ov-season" type="number" min="0" max="99" inputmode="numeric" formControlName="seasonNumber" />
                <span class="hint">0 = Spéciaux</span>
              </div>
              <div class="field">
                <label for="ov-episode">Épisode</label>
                <input id="ov-episode" type="number" min="0" max="9999" inputmode="numeric" formControlName="episodeNumber" />
              </div>
            </div>
          }
          @if (error(); as e) {
            <div class="alert alert-error" role="alert"><p>{{ e }}</p></div>
          }
          <div class="dialog-actions">
            <button type="button" (click)="close()">Annuler</button>
            <button type="submit" class="btn-primary" [disabled]="pending()">Enregistrer</button>
          </div>
        </form>
      }
    </dialog>
  `,
  styles: `
    .path { word-break: break-all; color: var(--color-text-muted); }
    fieldset { border: 0; padding: 0; margin: 0 0 var(--space-4); }
    .choice { display: flex; align-items: center; gap: var(--space-2); min-height: 2.5rem; }
  `,
})
export class OverrideDialog {
  private readonly admin = inject(AdminApi);
  private readonly library = inject(LibraryApi);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  /** Émis après un enregistrement réussi. */
  readonly saved = output<Issue>();

  protected readonly issue = signal<Issue | null>(null);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly suggestions = signal<string[]>([]);
  protected readonly titles$ = new Subject<string>();

  protected readonly form = new FormGroup({
    action: new FormControl<OverrideAction>('EPISODE', { nonNullable: true }),
    animeTitle: new FormControl('', { nonNullable: true }),
    seasonNumber: new FormControl<number | null>(1),
    episodeNumber: new FormControl<number | null>(null),
  });

  constructor() {
    // Suggestions de titres : recherche dans la bibliothèque (même recherche que la page Bibliothèque).
    this.titles$
      .pipe(
        map((q) => q.trim()),
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) =>
          q.length < 2
            ? of([])
            : this.library.animes({ q, size: 10 }).pipe(
                map((p) => p.items.map((a) => a.title)),
                catchError(() => of([])),
              ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((titles) => this.suggestions.set(titles));
  }

  open(issue: Issue): void {
    this.issue.set(issue);
    this.error.set(null);
    this.pending.set(false);
    this.form.reset({
      action: 'EPISODE',
      animeTitle: issue.animeTitle ?? '',
      seasonNumber: issue.seasonNumber ?? 1,
      episodeNumber: issue.episodeNumber ?? null,
    });
    // Le DOM du formulaire n'existe qu'une fois issue() défini : ouverture au tour suivant.
    queueMicrotask(() => openModal(this.dialog().nativeElement));
  }

  close(): void {
    closeModal(this.dialog().nativeElement);
  }

  save(): void {
    const issue = this.issue();
    if (!issue?.mediaFileId) {
      return;
    }
    const v = this.form.getRawValue();
    let request: OverrideRequest = { action: v.action };
    if (request.action === 'EPISODE') {
      const title = v.animeTitle.trim();
      if (!title || !isWhole(v.seasonNumber, 0, 99) || !isWhole(v.episodeNumber, 0, 9999)) {
        this.error.set('Indiquez l’animé, la saison (0 à 99) et l’épisode (0 à 9999).');
        return;
      }
      request = { ...request, animeTitle: title, seasonNumber: v.seasonNumber!, episodeNumber: v.episodeNumber! };
    }
    this.pending.set(true);
    this.error.set(null);
    this.admin.setOverride(issue.mediaFileId, request).subscribe({
      next: () => {
        this.pending.set(false);
        this.close();
        this.saved.emit(issue);
      },
      error: (err: unknown) => {
        this.pending.set(false);
        this.error.set(errorMessage(err, 'Correction impossible.'));
      },
    });
  }
}

function isWhole(n: number | null | undefined, min: number, max: number): boolean {
  return typeof n === 'number' && Number.isInteger(n) && n >= min && n <= max;
}
