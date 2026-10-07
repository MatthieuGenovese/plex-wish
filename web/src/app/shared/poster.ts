import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

/**
 * Affiche d'un animé (2:3). Sans affiche, ou si elle ne charge pas : couverture composée (dégradé tiré du titre et
 * titre écrit dessus), jamais un carré vide. Décorative (alt vide) : le titre est toujours écrit à côté.
 * {@code variant="round"} : portrait rond (comédiens), initiales sans photo.
 */
@Component({
  selector: 'app-poster',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (url() && !broken()) {
      <img [src]="url()" alt="" [attr.loading]="eager() ? null : 'lazy'" [attr.fetchpriority]="eager() ? 'high' : null"
           decoding="async" referrerpolicy="no-referrer" (error)="broken.set(true)" />
    } @else if (variant() === 'round') {
      <span class="ph ph-round" [style.--h]="hue()" aria-hidden="true" data-testid="poster-placeholder">{{ initials() }}</span>
    } @else {
      <span class="ph" [style.--h]="hue()" aria-hidden="true" data-testid="poster-placeholder"><span>{{ title() }}</span></span>
    }
  `,
  host: { class: 'poster', '[class.poster-round]': 'variant() === "round"' },
})
export class Poster {
  readonly title = input.required<string>();
  readonly url = input<string | null>(null);
  /** Image visible dès l'affichage (héros, fiche) : pas de chargement différé, priorité haute. */
  readonly eager = input(false);
  readonly variant = input<'cover' | 'round'>('cover');

  protected readonly broken = signal(false);

  protected readonly initials = computed(() => initials(this.title()));
  protected readonly hue = computed(() => hue(this.title()));
}

/** Initiales des deux premiers mots (« Haruka Satō » → « HS »). */
export function initials(title: string): string {
  return title
    .split(/[\s_\-:]+/)
    .filter((w) => /^[\p{L}\p{N}]/u.test(w))
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}

/** Teinte stable tirée du titre (0–359). */
export function hue(title: string): number {
  let h = 0;
  for (const ch of title) {
    h = (h * 31 + ch.charCodeAt(0)) % 360;
  }
  return h;
}
