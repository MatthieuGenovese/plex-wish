import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Component } from '@angular/core';
import { AdminPage, ADMIN_SECTIONS } from './admin';
import { a11yViolations } from '../../core/a11y-testing';

@Component({ template: '' })
class Empty {}

describe('Administration (P2.7)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'admin', component: AdminPage, children: ADMIN_SECTIONS.map((s) => ({ path: s.path, component: Empty })) }])],
    });
  });

  it('onglets et liste déroulante (téléphone) pointent sur la même section ; la liste navigue ; accessible', async () => {
    const router = TestBed.inject(Router);
    const harness = await RouterTestingHarness.create('/admin/users');
    const el = harness.routeNativeElement!;
    expect(el.querySelectorAll('.admin-tabs a')).toHaveLength(10);
    expect(el.querySelector('.admin-tabs a[aria-current=page]')?.textContent).toBe('Utilisateurs');
    const select = el.querySelector<HTMLSelectElement>('#admin-section')!;
    expect(select.value).toBe('users');

    select.value = 'media';
    select.dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();
    expect(router.url).toBe('/admin/media');
    expect(select.value).toBe('media');
    expect(await a11yViolations(el)).toEqual([]);
  });
});
