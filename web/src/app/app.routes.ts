import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard, installationGuard, setupGuard } from './core/guards';

// Chaque page est chargée à la demande (lazy loading).
export const routes: Routes = [
  {
    path: 'installation',
    title: 'Installation',
    canActivate: [installationGuard],
    loadComponent: () => import('./pages/setup/setup').then((m) => m.SetupPage),
  },
  {
    path: 'login',
    title: 'Connexion',
    canActivate: [setupGuard, guestGuard],
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    path: 'a-propos',
    title: 'À propos',
    loadComponent: () => import('./pages/about/about').then((m) => m.AboutPage),
  },
  {
    path: '',
    canActivate: [setupGuard],
    canActivateChild: [setupGuard, authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Accueil',
        loadComponent: () => import('./pages/home/home').then((m) => m.HomePage),
      },
      {
        path: 'anime',
        title: 'Bibliothèque',
        loadComponent: () => import('./pages/library/library').then((m) => m.LibraryPage),
      },
      {
        path: 'recherche',
        title: 'Rechercher',
        data: { searchMode: true },
        loadComponent: () => import('./pages/library/library').then((m) => m.LibraryPage),
      },
      {
        path: 'compte',
        title: 'Mon compte',
        loadComponent: () => import('./pages/account/account').then((m) => m.AccountPage),
      },
      {
        path: 'anime/:id',
        title: 'Anime',
        loadComponent: () => import('./pages/anime-detail/anime-detail').then((m) => m.AnimeDetailPage),
      },
      {
        path: 'personne/:id',
        title: 'Comédien',
        loadComponent: () => import('./pages/person/person').then((m) => m.PersonPage),
      },
      {
        path: 'admin',
        canActivate: [adminGuard],
        canActivateChild: [adminGuard],
        loadChildren: () => import('./pages/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
      },
      {
        path: '**',
        title: 'Page introuvable',
        loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
      },
    ],
  },
];
