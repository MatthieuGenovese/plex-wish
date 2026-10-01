import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ScanPage } from './scan';
import { ScanReport } from '../../core/api-types';

const report = (over: Partial<ScanReport>): ScanReport => ({
  id: 4, status: 'SUCCESS', startedAt: '2026-10-01T10:00:00Z', finishedAt: '2026-10-01T10:00:05Z', triggeredBy: 'admin',
  failureCode: null, failureReason: null, stats: null, issueCounts: {}, ...over,
});

describe('ScanPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ScanPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function open(latest: ScanReport) {
    const fixture = TestBed.createComponent(ScanPage);
    await fixture.whenStable();
    http.expectOne('/api/admin/library/scan-report').flush(latest);
    http.expectOne((r) => r.url === '/api/admin/library/scans').flush({ total: 1, page: 0, size: 20, items: [latest] });
    await fixture.whenStable();
    return fixture;
  }

  const el = (f: { nativeElement: HTMLElement }) => f.nativeElement;

  it('second lancement pendant un scan (409) : message clair', async () => {
    const fixture = await open(report({}));
    el(fixture).querySelector<HTMLButtonElement>('.btn-primary')!.click();
    http.expectOne('/api/admin/library/scan').flush(
      { status: 409, error: 'SCAN_ALREADY_RUNNING', message: 'Un scan est déjà en cours' },
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    http.expectOne('/api/admin/library/scan-report').flush(report({ id: 5, status: 'RUNNING', finishedAt: null }));
    await fixture.whenStable();
    expect(el(fixture).textContent).toContain('Un scan est déjà en cours : un seul à la fois.');
    expect(el(fixture).querySelector<HTMLButtonElement>('.btn-primary')!.disabled).toBe(true);
  });

  it('disparition massive : explication, puis relance seulement après confirmation explicite', async () => {
    const fixture = await open(report({
      status: 'FAILED', failureCode: 'MASS_REMOVAL',
      failureReason: '20000 fichiers connus sur 28000 (71 %) seraient marqués indisponibles',
    }));
    const page = el(fixture);
    expect(page.textContent).toContain('Scan arrêté par sécurité : rien n’a été modifié.');
    expect(page.textContent).toContain('71 %');

    page.querySelector<HTMLButtonElement>('.alert .btn-danger')!.click();
    const dialog = page.querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);

    // Annuler : aucun appel.
    dialog.returnValue = 'cancel';
    dialog.dispatchEvent(new Event('close'));
    http.expectNone('/api/admin/library/scan?confirmMassRemoval=true');

    // Confirmer : relance avec confirmMassRemoval=true.
    dialog.returnValue = 'confirm';
    dialog.dispatchEvent(new Event('close'));
    const req = http.expectOne((r) => r.url === '/api/admin/library/scan');
    expect(req.request.params.get('confirmMassRemoval')).toBe('true');
  });

  it('autre échec : raison affichée, pas de proposition de confirmation', async () => {
    const fixture = await open(report({ status: 'FAILED', failureCode: 'MEDIA_ROOT_UNAVAILABLE', failureReason: '/media vide — montage NAS absent ?' }));
    expect(el(fixture).textContent).toContain('montage NAS absent');
    expect(el(fixture).querySelector('.alert .btn-danger')).toBeNull();
  });
});
