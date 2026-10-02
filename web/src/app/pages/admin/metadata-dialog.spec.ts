import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MetadataDialog } from './metadata-dialog';
import { MetadataEntry } from '../../core/api-types';

const entry: MetadataEntry = {
  animeId: 7, title: 'Sousou no Frieren', status: 'MATCHED', reason: null, score: 1, locked: false,
  providerId: '154587', matchedTitle: 'Frieren: Beyond Journey’s End', year: 2023, posterUrl: null, metadataUrl: null,
  lastError: null, updatedAt: null, updatedBy: 'auto',
  candidates: [
    { providerId: '154587', title: 'Frieren: Beyond Journey’s End', romaji: 'Sousou no Frieren', year: 2023, format: 'TV', episodes: 28, posterUrl: null, siteUrl: null, score: 1 },
    { providerId: '182255', title: 'Frieren Season 2', romaji: 'Sousou no Frieren 2nd Season', year: 2026, format: 'TV', episodes: 10, posterUrl: null, siteUrl: null, score: 0.85 },
  ],
};

const conflict = {
  status: 409, error: 'METADATA_CONFLICT', message: 'Cet animé a déjà une fiche',
  anime: { id: 7, title: 'Sousou no Frieren' },
  current: { providerId: '154587', title: 'Frieren: Beyond Journey’s End', romaji: null, year: 2023, format: 'TV', episodes: 28, synopsis: null, posterUrl: null, siteUrl: null },
  proposed: { providerId: '182255', title: 'Frieren Season 2', romaji: null, year: 2026, format: 'TV', episodes: 10, synopsis: null, posterUrl: null, siteUrl: null },
  otherAnime: [],
};

describe('MetadataDialog', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [MetadataDialog], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function openWith(e: MetadataEntry) {
    const fixture = TestBed.createComponent(MetadataDialog);
    const messages: string[] = [];
    fixture.componentInstance.changed.subscribe((m) => messages.push(m));
    await fixture.whenStable();
    fixture.componentInstance.open(e);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, messages };
  }

  it('remplacer une fiche : montre l’actuelle et la nouvelle, puis confirme avec replace=true', async () => {
    const { fixture, el, messages } = await openWith(entry);
    el.querySelectorAll<HTMLInputElement>('input[type=radio]')[1].dispatchEvent(new Event('change'));
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.btn-primary')!.click();
    const first = http.expectOne('/api/admin/anime/7/metadata');
    expect(first.request.method).toBe('PUT');
    expect(first.request.body).toEqual({ providerId: '182255' });
    expect(first.request.params.has('replace')).toBe(false);
    first.flush(conflict, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    const box = el.querySelector('.conflict')!;
    expect(box.textContent).toContain('Remplacer la fiche actuelle ?');
    expect(box.textContent).toContain('Actuelle');
    expect(box.textContent).toContain('Frieren: Beyond Journey’s End 2023');
    expect(box.textContent).toContain('Frieren Season 2 2026');
    expect(messages).toEqual([]);

    el.querySelector<HTMLButtonElement>('.btn-danger')!.click();
    const confirmed = http.expectOne((r) => r.url === '/api/admin/anime/7/metadata');
    expect(confirmed.request.params.get('replace')).toBe('true');
    expect(confirmed.request.body).toEqual({ providerId: '182255' });
    confirmed.flush({ ...entry, status: 'MANUAL', locked: true, providerId: '182255', matchedTitle: 'Frieren Season 2' });
    expect(messages).toEqual(['« Sousou no Frieren » : fiche « Frieren Season 2 » appliquée et verrouillée.']);
  });

  it('identifiant saisi : prévisualisation, puis « aucune fiche » possible', async () => {
    const { fixture, el } = await openWith({ ...entry, status: 'UNMATCHED', providerId: null, candidates: [] });
    const radios = el.querySelectorAll<HTMLInputElement>('input[type=radio]');
    radios[0].dispatchEvent(new Event('change')); // autre identifiant
    await fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('#md-id')!;
    input.value = '154587';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Prévisualiser')!.click();
    const req = http.expectOne((r) => r.url === '/api/admin/anime/7/metadata/preview');
    expect(req.request.params.get('providerId')).toBe('154587');
    req.flush(conflict.current);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=md-preview]')?.textContent).toContain('Frieren: Beyond Journey’s End');

    el.querySelectorAll<HTMLInputElement>('input[type=radio]')[1].dispatchEvent(new Event('change')); // aucune fiche
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.btn-primary')!.click();
    expect(http.expectOne('/api/admin/anime/7/metadata').request.body).toEqual({ providerId: null });
  });
});
