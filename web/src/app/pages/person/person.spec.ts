import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { PersonPage } from './person';

describe('PersonPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [PersonPage], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open() {
    const fixture = TestBed.createComponent(PersonPage);
    fixture.componentRef.setInput('id', '95185');
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, req: http.expectOne('/api/people/95185') };
  }

  it('photo, noms, et les animés de la bibliothèque avec le personnage joué', async () => {
    const { fixture, el, req } = await open();
    req.flush({
      id: '95185', name: 'Atsumi Tanezaki', nativeName: '種﨑敦美', imageUrl: '/api/cast-images/p1', sourceUrl: 'https://anilist.co/staff/95185',
      roles: [
        { animeId: 7, animeTitle: 'Frieren', year: 2023, posterUrl: '/api/posters/x', role: 'MAIN',
          character: { name: 'Frieren', nativeName: null, imageUrl: null } },
        { animeId: 7, animeTitle: 'Frieren', year: 2023, posterUrl: '/api/posters/x', role: 'SUPPORTING',
          character: { name: 'Sein', nativeName: null, imageUrl: null } },
        { animeId: 9, animeTitle: 'Spy x Family', year: 2022, posterUrl: null, role: 'MAIN',
          character: { name: 'Anya Forger', nativeName: null, imageUrl: '/api/cast-images/c2' } },
      ],
    });
    await fixture.whenStable();
    expect(el.querySelector('h1')?.textContent).toBe('Atsumi Tanezaki');
    expect(el.querySelector('.native')?.textContent).toBe('種﨑敦美');
    expect(el.querySelector('.native')?.getAttribute('lang')).toBe('ja');
    expect(el.querySelector('.hero img')?.getAttribute('src')).toBe('/api/cast-images/p1');
    const cards = el.querySelectorAll('.grid li');
    expect(cards.length).toBe(2);
    expect(cards[0].querySelector('a')?.getAttribute('href')).toBe('/anime/7');
    expect(cards[1].textContent).toContain('Anya Forger');
    expect(cards[1].textContent).toContain('Principal');
    // Deux personnages dans le même animé : une seule carte.
    expect(cards[0].querySelectorAll('.role').length).toBe(2);
    expect(cards[0].textContent).toContain('Sein');
    expect(el.textContent).toContain('2 animés de la bibliothèque');
    expect(el.querySelector('.source a')?.getAttribute('href')).toBe('https://anilist.co/staff/95185');
  });

  it('404 : message clair', async () => {
    const { fixture, el, req } = await open();
    req.flush({ status: 404, error: 'PERSON_NOT_FOUND', message: 'Comédien introuvable' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(el.querySelector('.alert-error')?.textContent).toContain('aucun animé disponible');
  });
});
