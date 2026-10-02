import { Component, computed, input, signal } from '@angular/core';

/**
 * Affiche d'un animé (ratio 2:3). Sans affiche, ou si elle ne charge pas (lien mort, CDN injoignable) :
 * visuel de remplacement, couleur tirée du titre + initiales. Décoratif (alt vide) : le titre est affiché à côté.
 */
@Component({
  selector: 'app-poster',
  template: `
    @if (url() && !broken()) {
      <img class="poster" [src]="url()" alt="" [attr.loading]="eager() ? null : 'lazy'" decoding="async"
           referrerpolicy="no-referrer" (error)="broken.set(true)" />
    } @else {
      <span class="poster placeholder" [style.--hue]="hue()" aria-hidden="true" data-testid="poster-placeholder">{{ initials() }}</span>
    }
  `,
  styles: `
    /* Conteneur : les initiales suivent la largeur de l'affiche (grille, fiche, vignette d'admin). */
    :host { display: block; container-type: inline-size; }
    .poster {
      display: block;
      width: 100%;
      aspect-ratio: 2 / 3;
      border-radius: var(--radius);
      object-fit: cover;
      background: var(--color-surface-raised);
    }
    .placeholder {
      display: grid;
      place-items: center;
      background: hsl(var(--hue) var(--poster-saturation) var(--poster-lightness));
      color: var(--color-text);
      font-size: clamp(var(--font-size-xs), 22cqi, var(--font-size-xxl));
      font-weight: var(--font-weight-bold);
      letter-spacing: 0.05em;
    }
  `,
})
export class Poster {
  readonly title = input.required<string>();
  readonly url = input<string | null>(null);
  /** Image visible dès l'affichage (fiche anime) : pas de chargement différé. */
  readonly eager = input(false);

  protected readonly broken = signal(false);

  protected readonly initials = computed(() =>
    this.title()
      .split(/[\s_\-:]+/)
      .filter((w) => /^[\p{L}\p{N}]/u.test(w))
      .slice(0, 2)
      .map((w) => w[0].toUpperCase())
      .join(''),
  );

  protected readonly hue = computed(() => {
    let h = 0;
    for (const ch of this.title()) {
      h = (h * 31 + ch.charCodeAt(0)) % 360;
    }
    return h;
  });
}
