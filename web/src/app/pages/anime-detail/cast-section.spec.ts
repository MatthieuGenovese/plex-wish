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

  it('personnage, rôle, comédien (lien vers sa page), repli visuel sans image, source', async () => {
    const el = await open({
      source: 'AniList', sourceUrl: 'https://anilist.co/anime/154587',
      items: [
        { character: { name: 'Frieren', nativeName: 'フリーレン', imageUrl: '/api/cast-images/abc' }, role: 'MAIN', language: 'ja',
          person: { id: '95185', name: 'Atsumi Tanezaki', nativeName: '種﨑敦美', imageUrl: null } },
        { character: { name: 'Narrateur', nativeName: null, imageUrl: null }, role: 'SUPPORTING', language: 'ja', person: null },
      ],
    });
    const entries = el.querySelectorAll('.entry');
    expect(entries.length).toBe(2);
    expect(entries[0].querySelector('.name')?.textContent).toBe('Frieren');
    expect(entries[0].querySelector('.badge')?.textContent).toBe('Principal');
    expect(entries[0].querySelector('img')?.getAttribute('src')).toBe('/api/cast-images/abc');
    expect(entries[0].querySelector('a.person')?.getAttribute('href')).toBe('/personne/95185');
    expect(entries[0].querySelector('.face [data-testid=poster-placeholder]')?.textContent).toBe('AT');
    expect(entries[1].querySelector('.badge')?.textContent).toBe('Secondaire');
    expect(entries[1].textContent).toContain('Voix non renseignée');
    expect(el.querySelector('.source a')?.getAttribute('href')).toBe('https://anilist.co/anime/154587');
  });

  it('sans distribution : rien n’est affiché', async () => {
    const el = await open({ source: null, sourceUrl: null, items: [] });
    expect(el.querySelector('section')).toBeNull();
  });
});
