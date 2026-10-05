import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CastSection } from './cast-section';

describe('CastSection', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CastSection], providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(body: object) {
    const fixture = TestBed.createComponent(CastSection);
    fixture.componentRef.setInput('animeId', '7');
    await fixture.whenStable();
    http.expectOne('/api/anime/7/cast').flush(body);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('photo et nom du doubleur (lien vers sa page), personnage joué, rôle ; pas d’image de personnage', async () => {
    const el = await open({
      source: 'AniList', sourceUrl: 'https://anilist.co/anime/154587',
      items: [
        { character: { name: 'Frieren', nativeName: 'フリーレン', imageUrl: '/api/cast-images/perso' }, role: 'MAIN', language: 'ja',
          person: { id: '95185', name: 'Atsumi Tanezaki', nativeName: '種﨑敦美', imageUrl: '/api/cast-images/voix' } },
        { character: { name: 'Narrateur', nativeName: null, imageUrl: '/api/cast-images/perso2' }, role: 'SUPPORTING', language: 'ja', person: null },
      ],
    });
    const entries = el.querySelectorAll('.entry');
    expect(entries.length).toBe(2);
    const first = entries[0];
    expect(first.querySelector('a')?.getAttribute('href')).toBe('/personne/95185');
    expect([...first.querySelectorAll('img')].map((i) => i.getAttribute('src'))).toEqual(['/api/cast-images/voix']);
    expect(first.querySelector('.name')?.textContent).toBe('Atsumi Tanezaki');
    expect(first.querySelector('.character')?.textContent).toBe('Frieren');
    expect(first.querySelector('.badge')?.textContent).toBe('Principal');
    expect(first.querySelector('a')?.getAttribute('aria-label')).toBe('Atsumi Tanezaki, voix de Frieren (Principal)');
    // Sans doubleur : visuel de remplacement, jamais l'image du personnage, pas de lien.
    expect(entries[1].querySelector('img')).toBeNull();
    expect(entries[1].querySelector('a')).toBeNull();
    expect(entries[1].textContent).toContain('Voix non renseignée');
    expect(entries[1].querySelector('.character')?.textContent).toBe('Narrateur');
    expect(el.querySelector('.source a')?.getAttribute('href')).toBe('https://anilist.co/anime/154587');
  });

  it('sans distribution : rien n’est affiché', async () => {
    const el = await open({ source: null, sourceUrl: null, items: [] });
    expect(el.querySelector('section')).toBeNull();
  });
});
