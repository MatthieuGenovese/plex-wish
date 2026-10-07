import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { Icon } from './icon';

/**
 * Champ de recherche (pilule). Sans {@code (search)} : Entrée ouvre la bibliothèque filtrée (barre du haut).
 * Avec : chaque frappe est transmise (page Bibliothèque, l'appelant attend 300 ms avant de chercher).
 */
@Component({
  selector: 'app-search-box',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form role="search" class="search-field" (submit)="submit($event)">
      <app-icon name="search" />
      <label class="visually-hidden" [attr.for]="id()">Rechercher un animé</label>
      <input #field [id]="id()" type="search" [value]="value() ?? ''" placeholder="Rechercher un animé" autocomplete="off"
             spellcheck="false" enterkeyhint="search" (input)="typed.emit(field.value)" />
      @if (field.value) {
        <button type="button" class="btn-icon" aria-label="Effacer la recherche" (click)="clear()"><app-icon name="close" size="sm" /></button>
      }
    </form>
  `,
})
export class SearchBox {
  private readonly router = inject(Router);
  readonly id = input('recherche-globale');
  readonly value = input<string | null | undefined>('');
  /** Recherche au fil de la frappe (page Bibliothèque). */
  readonly typed = output<string>();
  /** true : Entrée ouvre /anime?q= ; false : seul (typed) est émis. */
  readonly navigates = input(true);
  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  focus(): void {
    this.field().nativeElement.focus();
  }

  submit(event: Event): void {
    event.preventDefault();
    const q = this.field().nativeElement.value.trim();
    if (this.navigates()) {
      this.router.navigate(['/anime'], { queryParams: q ? { q } : {} });
      this.field().nativeElement.blur();
    } else {
      this.typed.emit(q);
    }
  }

  clear(): void {
    this.field().nativeElement.value = '';
    this.typed.emit('');
    this.field().nativeElement.focus();
  }
}
