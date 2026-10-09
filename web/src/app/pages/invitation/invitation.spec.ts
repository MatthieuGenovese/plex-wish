import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { InvitationPage } from './invitation';

describe('Lien d’invitation (D1.4)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    history.replaceState(null, '', '/invitation#jeton-de-test_0123456789');
    TestBed.configureTestingModule({
      imports: [InvitationPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function type(el: HTMLElement, selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('le jeton quitte l’adresse, part dans le corps ; la personne choisit son mot de passe', async () => {
    const fixture = TestBed.createComponent(InvitationPage);
    expect(location.hash).toBe('');
    const check = http.expectOne('/api/invitation/check');
    expect(check.request.body).toEqual({ token: 'jeton-de-test_0123456789' });
    check.flush({ username: 'alice', purpose: 'INVITE', expiresAt: '2026-10-12T10:00:00Z' });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')!.textContent).toContain('Bienvenue, alice');
    type(el, '#inv-pass', 'mon-mot-de-passe');
    type(el, '#inv-pass2', 'mon-mot-de-passe');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    const accept = http.expectOne('/api/invitation/accept');
    expect(accept.request.body).toEqual({ token: 'jeton-de-test_0123456789', password: 'mon-mot-de-passe' });
    accept.flush({ username: 'alice' });
    await fixture.whenStable();
    expect(el.querySelector('h1')!.textContent).toContain('Mot de passe enregistré');
  });

  it('lien déjà utilisé ou expiré : message clair, pas de formulaire', async () => {
    const fixture = TestBed.createComponent(InvitationPage);
    http.expectOne('/api/invitation/check').flush(
      { status: 410, error: 'INVITATION_INVALID', message: 'Ce lien n’est plus valable (déjà utilisé, expiré ou remplacé).' },
      { status: 410, statusText: 'Gone' },
    );
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('h1')!.textContent).toContain('Lien non valable');
    expect(el.querySelector('form')).toBeNull();
  });
});
