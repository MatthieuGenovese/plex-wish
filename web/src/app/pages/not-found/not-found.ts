import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { StateBox } from '../../shared/state';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink, StateBox],
  template: `
    <h1 class="visually-hidden">Page introuvable</h1>
    <app-state icon="explore" heading="Page introuvable" message="Le lien est peut-être ancien, ou la page a été déplacée.">
      <a class="btn btn-primary" routerLink="/">Retour à l’accueil</a>
      <a class="btn btn-ghost" routerLink="/anime">Bibliothèque</a>
    </app-state>
  `,
})
export class NotFoundPage {}
