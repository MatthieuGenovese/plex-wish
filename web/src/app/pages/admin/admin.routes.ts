import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./admin').then((m) => m.AdminPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'scan' },
      {
        path: 'scan',
        title: 'Scan · Administration',
        loadComponent: () => import('./scan').then((m) => m.ScanPage),
      },
      {
        path: 'report',
        title: 'Rapport de scan · Administration',
        loadComponent: () => import('./report').then((m) => m.ReportPage),
      },
      {
        path: 'corrections',
        title: 'Corrections · Administration',
        loadComponent: () => import('./overrides').then((m) => m.OverridesPage),
      },
      {
        path: 'metadata',
        title: 'Métadonnées · Administration',
        loadComponent: () => import('./metadata').then((m) => m.MetadataPage),
      },
      {
        path: 'tmdb',
        title: 'Synopsis français · Administration',
        loadComponent: () => import('./tmdb').then((m) => m.TmdbPage),
      },
      {
        path: 'posters',
        title: 'Affiches · Administration',
        loadComponent: () => import('./posters').then((m) => m.PostersPage),
      },
      {
        path: 'cast',
        title: 'Distribution · Administration',
        loadComponent: () => import('./cast').then((m) => m.CastPage),
      },
      {
        path: 'media',
        title: 'Médias · Administration',
        loadComponent: () => import('./media').then((m) => m.MediaPage),
      },
      {
        path: 'settings',
        title: 'Réglages · Administration',
        loadComponent: () => import('./settings').then((m) => m.SettingsPage),
      },
      {
        path: 'users',
        title: 'Utilisateurs · Administration',
        loadComponent: () => import('./users').then((m) => m.UsersPage),
      },
    ],
  },
];
