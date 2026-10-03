import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { PostersPage } from './posters';
import { formatBytes } from '../../shared/format';

const summary = {
  enabled: true, folderUsable: true, folder: '/data/posters', total: 4, local: 2, localTmdb: 1, localAniList: 1,
  remote: 1, missing: 1, failed: 1, diskBytes: 152_000_000, averageBytes: 110_000, estimatedBytes: 143_000_000,
  freeBytes: 2_000_000_000_000, pausedUntil: null, lastUnavailable: null,
};

describe('formatBytes', () => {
  it('unités décimales, en français', () => {
    expect(formatBytes(950)).toBe('950 o');
    expect(formatBytes(110_000)).toBe('110 Ko');
    expect(formatBytes(1_234_000_000)).toBe('1,2 Go');
    expect(formatBytes(-1)).toBe('—');
  });
});

describe('PostersPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PostersPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('compteurs, place disque et retéléchargement', async () => {
    const fixture = TestBed.createComponent(PostersPage);
    fixture.componentRef.setInput('filtre', 'failed');
    await fixture.whenStable();
    http.expectOne('/api/admin/posters/summary').flush(summary);
    const list = http.expectOne((r) => r.url === '/api/admin/posters');
    expect(list.request.params.get('filter')).toBe('failed');
    list.flush({
      total: 1, page: 0, size: 50, items: [{
        animeId: 5, title: 'Html', state: 'REMOTE', provider: 'ANILIST', sourceUrl: 'https://s4.anilist.co/x.jpg',
        posterUrl: 'https://s4.anilist.co/x.jpg', failed: true, lastError: 'pas une image acceptée (text/html)', bytes: null, fetchedAt: null,
      }],
    });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect([...el.querySelectorAll('.tile')].map((t) => t.textContent?.replace(/\s+/g, ' ').trim()))
      .toEqual(['2Sur le NAS', '1Distantes', '1Sans affiche', '1En échec']);
    expect(el.querySelector('[data-testid=disk]')?.textContent?.replace(/\s+/g, ' ')).toContain('152 Mo utilisés');
    expect(el.querySelector('[data-testid=disk]')?.textContent).toContain('143 Mo');
    expect(el.querySelector('tbody')?.textContent).toContain('text/html');

    el.querySelector<HTMLButtonElement>('tbody button')!.click();
    http.expectOne('/api/admin/anime/5/poster/redownload').flush({ queued: true, running: true });
    await fixture.whenStable();
    http.expectOne('/api/admin/posters/summary').flush(summary);
    http.expectOne((r) => r.url === '/api/admin/posters').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect(el.textContent).toContain('« Html » : affiche remise en file');
  });

  it('dossier inutilisable : avertissement explicite', async () => {
    const fixture = TestBed.createComponent(PostersPage);
    await fixture.whenStable();
    http.expectOne('/api/admin/posters/summary').flush({ ...summary, folderUsable: false });
    http.expectOne((r) => r.url === '/api/admin/posters').flush({ total: 0, page: 0, size: 50, items: [] });
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('.alert-error')?.textContent).toContain('POSTERS_HOST_PATH');
  });
});
