import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HomePage } from './home';

describe('HomePage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function statusText(fixture: { nativeElement: HTMLElement }): string {
    return fixture.nativeElement.querySelector('[role=status]')?.textContent?.trim() ?? '';
  }

  it('affiche « API joignable » quand /api/status répond UP', () => {
    const fixture = TestBed.createComponent(HomePage);
    http.expectOne('/api/status').flush({ status: 'UP' });
    fixture.detectChanges();
    expect(statusText(fixture)).toBe('API joignable');
  });

  it('affiche « API injoignable » en cas d’erreur', () => {
    const fixture = TestBed.createComponent(HomePage);
    http.expectOne('/api/status').flush('boom', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(statusText(fixture)).toBe('API injoignable');
  });
});
