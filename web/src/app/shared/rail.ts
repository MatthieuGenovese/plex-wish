import { ChangeDetectionStrategy, Component, ElementRef, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from './icon';

/**
 * Rangée horizontale : titre, « Tout voir », contenu qui défile (doigt, molette, clavier, flèches sur ordinateur).
 * Le contenu est une liste (<ul>) fournie par l'appelant ; les flèches se désactivent aux extrémités.
 */
@Component({
  selector: 'app-rail',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="rail" [attr.aria-labelledby]="id()">
      <div class="rail-head">
        <h2 [id]="id()">{{ heading() }}</h2>
        @if (moreLink(); as link) {
          <a class="rail-more" [routerLink]="link" [queryParams]="moreParams()">Tout voir<span class="visually-hidden"> : {{ heading() }}</span>
            <app-icon name="chevron_right" size="sm" /></a>
        }
      </div>
      <div class="rail-body">
        <button type="button" class="rail-arrow rail-prev" [disabled]="atStart()" (click)="scroll(-1)" tabindex="-1"
                aria-hidden="true"><app-icon name="chevron_left" /></button>
        <div class="rail-track" #track (scroll)="update()">
          <ng-content />
        </div>
        <button type="button" class="rail-arrow rail-next" [disabled]="atEnd()" (click)="scroll(1)" tabindex="-1"
                aria-hidden="true"><app-icon name="chevron_right" /></button>
      </div>
    </section>
  `,
})
export class Rail {
  readonly heading = input.required<string>();
  readonly id = input.required<string>();
  readonly moreLink = input<string | null>(null);
  readonly moreParams = input<Record<string, string> | null>(null);
  private readonly track = viewChild.required<ElementRef<HTMLElement>>('track');
  protected readonly atStart = signal(true);
  protected readonly atEnd = signal(false);

  scroll(direction: number): void {
    const el = this.track().nativeElement;
    el.scrollBy({ left: direction * el.clientWidth * 0.85, behavior: 'smooth' });
  }

  update(): void {
    const el = this.track().nativeElement;
    this.atStart.set(el.scrollLeft <= 4);
    this.atEnd.set(el.scrollLeft + el.clientWidth >= el.scrollWidth - 4);
  }

  ngAfterViewInit(): void {
    // Après le rendu du contenu : flèche « suivant » active seulement s'il y a de quoi défiler.
    setTimeout(() => this.update(), 0);
  }
}
