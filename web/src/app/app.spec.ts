import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('affiche la navigation principale vers les pages de l’étape', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('nav a'),
    ).map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/', '/anime', '/admin', '/login']);
  });

  it('déclare toutes les routes demandées', () => {
    expect(routes.map((r) => r.path)).toEqual(['', 'login', 'anime', 'anime/:id', 'admin', '**']);
  });
});
