import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ICONS, IconName } from './icons';

/**
 * Icône SVG en ligne (Material Symbols Rounded). Décorative (aria-hidden) : le texte ou l'aria-label du bouton
 * qui la contient la décrit. Taille : 1.5em par défaut (suit le texte), couleur : celle du texte.
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg viewBox="0 -960 960 960" aria-hidden="true" focusable="false"><path [attr.d]="path()" /></svg>`,
  host: { class: 'icon', '[class.icon-sm]': 'size() === "sm"', '[class.icon-lg]': 'size() === "lg"' },
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input<'sm' | 'md' | 'lg'>('md');
  protected readonly path = computed(() => ICONS[this.name()]);
}
