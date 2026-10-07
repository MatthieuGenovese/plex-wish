import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';
import { pageTitle } from './title-strategy';
import { APP_NAME } from './app-name';
import { StateBox } from '../shared/state';
import { Icon } from '../shared/icon';
import { a11yViolations } from './a11y-testing';

describe('Fondations (P2.1)', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('thème : sombre par défaut, choix enregistré sur l’appareil et appliqué à <html>', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    theme.set('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(localStorage.getItem('theme')).toBe('light');
    theme.set('system');
    expect(['dark', 'light']).toContain(document.documentElement.getAttribute('data-theme'));
  });

  it('nom de l’application : une seule constante dans les titres d’onglet', () => {
    expect(pageTitle('Accueil')).toBe(`Accueil · ${APP_NAME}`);
    expect(pageTitle(null)).toBe(APP_NAME);
  });

  it('état vide et icône : accessibles (axe), icône décorative', async () => {
    const fixture = TestBed.createComponent(StateBox);
    fixture.componentRef.setInput('heading', 'Aucun animé');
    fixture.componentRef.setInput('message', 'La bibliothèque est vide.');
    fixture.componentRef.setInput('icon', 'search');
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Aucun animé');
    expect(el.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(await a11yViolations(el)).toEqual([]);

    const icon = TestBed.createComponent(Icon);
    icon.componentRef.setInput('name', 'play_arrow_fill');
    await icon.whenStable();
    expect((icon.nativeElement as HTMLElement).querySelector('path')?.getAttribute('d')).toMatch(/^M/);
  });
});
