import { Component, computed, input, output } from '@angular/core';

/** Pagination : précédent / suivant + numéros autour de la page courante (pages numérotées à partir de 0). */
@Component({
  selector: 'app-pager',
  template: `
    @if (pageCount() > 1) {
      <nav class="pager" [attr.aria-label]="label()">
        <button type="button" class="btn-small" [disabled]="page() === 0" (click)="go(page() - 1)">
          ‹ <span class="visually-hidden">Page</span> précédente
        </button>
        <ul>
          @for (p of pages(); track $index) {
            <li>
              @if (p === null) {
                <span class="gap" aria-hidden="true">…</span>
              } @else {
                <button type="button" class="btn-small" [class.current]="p === page()"
                        [attr.aria-current]="p === page() ? 'page' : null"
                        [attr.aria-label]="'Page ' + (p + 1)" (click)="go(p)">{{ p + 1 }}</button>
              }
            </li>
          }
        </ul>
        <button type="button" class="btn-small" [disabled]="page() >= pageCount() - 1" (click)="go(page() + 1)">
          <span class="visually-hidden">Page</span> suivante ›
        </button>
      </nav>
    }
  `,
  styles: `
    .pager { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; gap: var(--space-2); margin: var(--space-4) 0; }
    ul { display: flex; flex-wrap: wrap; gap: var(--space-1); margin: 0; padding: 0; list-style: none; }
    button { min-width: 2.75rem; }
    .current { background: var(--color-accent); border-color: var(--color-accent); color: var(--color-on-accent); }
    .current:hover:not(:disabled) { background: var(--color-accent-strong); color: var(--color-on-accent); }
    .gap { display: inline-block; padding: 0 var(--space-1); color: var(--color-text-muted); }
  `,
})
export class Pager {
  readonly page = input.required<number>();
  readonly total = input.required<number>();
  readonly size = input.required<number>();
  readonly label = input('Pagination');
  readonly pageChange = output<number>();

  protected readonly pageCount = computed(() => Math.max(1, Math.ceil(this.total() / this.size())));

  /** Première, dernière, et deux pages de chaque côté de la page courante ; null = « … ». */
  protected readonly pages = computed(() => pageWindow(this.page(), this.pageCount()));

  go(p: number): void {
    if (p >= 0 && p < this.pageCount() && p !== this.page()) {
      this.pageChange.emit(p);
    }
  }
}

export function pageWindow(page: number, count: number): (number | null)[] {
  const out: (number | null)[] = [];
  for (let p = 0; p < count; p++) {
    if (p === 0 || p === count - 1 || Math.abs(p - page) <= 2) {
      out.push(p);
    } else if (out[out.length - 1] !== null) {
      out.push(null);
    }
  }
  return out;
}
