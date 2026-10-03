import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/guards';

// Chaque page est chargée à la demande (lazy loading).
export const routes: Routes = [
  {
    path: 'login',
    title: 'Connexion · Anime Server',
    canActivate: [guestGuard],
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    path: 'a-propos',
    title: 'À propos · Anime Server',
    loadComponent: () => import('./pages/about/about').then((m) => m.AboutPage),
  },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Accueil · Anime Server',
        loadComponent: () => import('./pages/home/home').then((m) => m.HomePage),
      },
      {
        path: 'anime',
        title: 'Bibliothèque · Anime Server',
        loadComponent: () => import('./pages/library/library').then((m) => m.LibraryPage),
      },
      {
        path: 'anime/:id',
        title: 'Anime · Anime Server',
        loadComponent: () => import('./pages/anime-detail/anime-detail').then((m) => m.AnimeDetailPage),
      },
      {
        path: 'personne/:id',
        title: 'Comédien · Anime Server',
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
        title: 'Page introuvable · Anime Server',
        loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
      },
    ],
  },
];
