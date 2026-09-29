import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <h1>Page introuvable</h1>
    <p><a routerLink="/">Retour à l'accueil</a></p>
  `,
})
export class NotFoundPage {}
