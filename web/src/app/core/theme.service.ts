import { Injectable, computed, signal } from '@angular/core';

export type ThemePreference = 'system' | 'dark' | 'light';

const KEY = 'theme';
const THEME_COLORS = { dark: '#0d0f14', light: '#f5f6f8' } as const;

/**
 * Thème de l'interface : sombre par défaut, clair, ou celui du système. Enregistré sur cet appareil seulement
 * (localStorage, aucune donnée envoyée au serveur) ; appliqué avant le premier rendu par public/theme-init.js.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly media = typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: light)') : null;
  private readonly systemLight = signal(this.media?.matches ?? false);
  readonly preference = signal<ThemePreference>(read());
  readonly effective = computed<'dark' | 'light'>(() => {
    const p = this.preference();
    return p === 'system' ? (this.systemLight() ? 'light' : 'dark') : p;
  });

  constructor() {
    this.media?.addEventListener?.('change', (e) => {
      this.systemLight.set(e.matches);
      this.apply();
    });
    this.apply();
  }

  set(pref: ThemePreference): void {
    this.preference.set(pref);
    try {
      localStorage.setItem(KEY, pref);
    } catch {
      // stockage indisponible (navigation privée) : le choix vaut pour cette visite
    }
    this.apply();
  }

  private apply(): void {
    const theme = this.effective();
    document.documentElement.setAttribute('data-theme', theme);
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme]);
  }
}

function read(): ThemePreference {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'system' || v === 'light' || v === 'dark' ? v : 'dark';
  } catch {
    return 'dark';
  }
}
