import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { LoginPage } from './login';
import { tokens } from '../../core/testing';

describe('LoginPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function submit(fixture: ReturnType<typeof TestBed.createComponent<LoginPage>>): void {
    const el = fixture.nativeElement as HTMLElement;
    const login = el.querySelector<HTMLInputElement>('#login')!;
    const password = el.querySelector<HTMLInputElement>('#password')!;
    login.value = 'alice';
    login.dispatchEvent(new Event('input'));
    password.value = 'pw';
    password.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
  }

  it('verrouillage anti brute force : message clair avec le délai donné par le serveur', async () => {
    const fixture = TestBed.createComponent(LoginPage);
    await fixture.whenStable();
    submit(fixture);
    http.expectOne('/api/auth/login').flush(
      { status: 429, error: 'TOO_MANY_ATTEMPTS', message: 'Trop de tentatives de connexion. Réessayez dans 15 minutes.' },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await fixture.whenStable();
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role=alert]')!;
    expect(alert.textContent).toContain('Réessayez dans 15 minutes.');
    expect(alert.textContent).toContain('temporairement bloquées');
  });

  it('connexion réussie : retour à la page demandée', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(LoginPage);
    fixture.componentRef.setInput('returnUrl', '/anime/3');
    await fixture.whenStable();
    submit(fixture);
    http.expectOne('/api/auth/login').flush(tokens('t'));
    expect(navigate).toHaveBeenCalledWith('/anime/3');
  });
});
