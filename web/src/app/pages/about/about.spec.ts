import { TestBed } from '@angular/core/testing';
import { AboutPage, TMDB_NOTICE } from './about';

describe('AboutPage', () => {
  it('affiche la mention TMDB exacte, et « TMDB » en texte si le logo manque', async () => {
    const fixture = TestBed.createComponent(AboutPage);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=tmdb-notice]')?.textContent?.trim()).toBe(
      'This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.');
    expect(TMDB_NOTICE).toContain('not endorsed');
    const logo = el.querySelector('img.tmdb-logo')!;
    expect(logo.getAttribute('alt')).toBe('TMDB');
    logo.dispatchEvent(new Event('error'));
    await fixture.whenStable();
    expect(el.querySelector('img.tmdb-logo')).toBeNull();
    expect(el.querySelector('#about-tmdb')?.textContent?.trim()).toBe('TMDB');
  });
});
