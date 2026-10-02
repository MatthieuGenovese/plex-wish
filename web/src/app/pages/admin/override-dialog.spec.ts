import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OverrideDialog } from './override-dialog';
import { Issue } from '../../core/api-types';

const issue: Issue = {
  id: 1, mediaFileId: 12, category: 'UNRESOLVED', animeTitle: 'Show', relativePath: 'Show/Mystery.mkv',
  detail: null, seasonNumber: null, episodeNumber: null, keptRelativePath: null, seasonSource: null, keptSeasonSource: null,
};

const conflict = {
  status: 409,
  error: 'EPISODE_ALREADY_LINKED',
  message: 'Cet épisode est déjà fourni par un autre fichier.',
  episode: { animeTitle: 'Show', seasonNumber: 1, episodeNumber: 1 },
  currentFiles: [{ mediaFileId: 5, relativePath: 'Show/Show - S01E01.mkv', viaOverride: false }],
  targetFile: { mediaFileId: 12, relativePath: 'Show/Mystery.mkv', viaOverride: false },
};

describe('OverrideDialog : remplacement d’un épisode déjà lié', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [OverrideDialog], providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function openAndSubmit() {
    const fixture = TestBed.createComponent(OverrideDialog);
    const saved: Issue[] = [];
    fixture.componentInstance.saved.subscribe((i) => saved.push(i));
    await fixture.whenStable();
    fixture.componentInstance.open(issue);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const episode = el.querySelector<HTMLInputElement>('#ov-episode')!;
    episode.value = '1';
    episode.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    return { fixture, el, saved };
  }

  it('409 : montre le fichier délié et son remplaçant ; rien n’est remplacé sans confirmation', async () => {
    const { fixture, el, saved } = await openAndSubmit();
    const first = http.expectOne((r) => r.url === '/api/admin/library/files/12/override');
    expect(first.request.params.has('replace')).toBe(false);
    expect(first.request.body).toEqual({ action: 'EPISODE', animeTitle: 'Show', seasonNumber: 1, episodeNumber: 1 });
    first.flush(conflict, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    const box = el.querySelector('.conflict')!;
    expect(box.textContent).toContain('Show · saison 1 · épisode 1');
    expect(box.textContent).toContain('Serait délié');
    expect(box.textContent).toContain('Show/Show - S01E01.mkv');
    expect(box.textContent).toContain('Le remplace');
    expect(box.textContent).toContain('Show/Mystery.mkv');
    expect(saved).toEqual([]);

    // Retour : on revient au formulaire, sans appel.
    [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Retour')!.click();
    await fixture.whenStable();
    expect(el.querySelector('.conflict')).toBeNull();
    http.expectNone((r) => r.url === '/api/admin/library/files/12/override');
  });

  it('confirmation : même correction renvoyée avec replace=true', async () => {
    const { fixture, el, saved } = await openAndSubmit();
    http.expectOne((r) => r.url === '/api/admin/library/files/12/override').flush(conflict, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    el.querySelector<HTMLButtonElement>('.btn-danger')!.click();
    const replace = http.expectOne((r) => r.url === '/api/admin/library/files/12/override');
    expect(replace.request.params.get('replace')).toBe('true');
    expect(replace.request.body).toEqual({ action: 'EPISODE', animeTitle: 'Show', seasonNumber: 1, episodeNumber: 1 });
    replace.flush({ mediaFileId: 12, relativePath: 'Show/Mystery.mkv', action: 'EPISODE' });
    expect(saved).toEqual([issue]);
  });
});
