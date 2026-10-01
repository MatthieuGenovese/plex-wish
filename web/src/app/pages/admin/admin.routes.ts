import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    title: 'Administration · Anime Server',
    loadComponent: () => import('./admin').then((m) => m.AdminPage),
  },
];
