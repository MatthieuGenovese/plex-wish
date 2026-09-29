import { Component, inject, signal } from '@angular/core';
import { StatusApi } from '../../core/status-api';

type ApiState = 'loading' | 'up' | 'down';

@Component({
  selector: 'app-home',
  template: `
    <h1>Accueil</h1>
    <p>Récemment ajoutés et bibliothèque : à venir (phase 4).</p>

    <section class="card" aria-labelledby="api-title">
      <h2 id="api-title">État du serveur</h2>
      <p role="status" [attr.data-state]="state()">
        @switch (state()) {
          @case ('loading') { Vérification…}
          @case ('up') { <span class="ok">API joignable</span> }
          @case ('down') { <span class="ko">API injoignable</span> }
        }
      </p>
    </section>
  `,
  styles: `
    .ok { color: var(--success); font-weight: 600; }
    .ko { color: var(--danger); font-weight: 600; }
  `,
})
export class HomePage {
  protected readonly state = signal<ApiState>('loading');

  constructor() {
    inject(StatusApi)
      .get()
      .subscribe({
        next: (s) => this.state.set(s.status === 'UP' ? 'up' : 'down'),
        error: () => this.state.set('down'),
      });
  }
}
