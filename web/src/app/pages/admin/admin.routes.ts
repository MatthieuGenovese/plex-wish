import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./admin').then((m) => m.AdminPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'scan' },
      {
        path: 'scan',
        title: 'Scan · Administration · Anime Server',
        loadComponent: () => import('./scan').then((m) => m.ScanPage),
      },
      {
        path: 'report',
        title: 'Rapport de scan · Administration · Anime Server',
        loadComponent: () => import('./report').then((m) => m.ReportPage),
      },
      {
        path: 'corrections',
        title: 'Corrections · Administration · Anime Server',
        loadComponent: () => import('./overrides').then((m) => m.OverridesPage),
      },
      {
        path: 'metadata',
        title: 'Métadonnées · Administration · Anime Server',
        loadComponent: () => import('./metadata').then((m) => m.MetadataPage),
      },
      {
        path: 'tmdb',
        title: 'Synopsis français · Administration · Anime Server',
        loadComponent: () => import('./tmdb').then((m) => m.TmdbPage),
      },
      {
        path: 'users',
        title: 'Utilisateurs · Administration · Anime Server',
        loadComponent: () => import('./users').then((m) => m.UsersPage),
      },
    ],
  },
];
