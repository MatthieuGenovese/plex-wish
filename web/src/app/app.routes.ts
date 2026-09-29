import { Routes } from '@angular/router';

// Chaque page est chargée à la demande (lazy loading).
// Les guards (auth, admin) arrivent en phase 4.
export const routes: Routes = [
  {
    path: '',
    title: 'Accueil · Anime Server',
    loadComponent: () => import('./pages/home/home').then((m) => m.HomePage),
  },
  {
    path: 'login',
    title: 'Connexion · Anime Server',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
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
    path: 'admin',
    title: 'Administration · Anime Server',
    loadComponent: () => import('./pages/admin/admin').then((m) => m.AdminPage),
  },
  {
    path: '**',
    title: 'Page introuvable · Anime Server',
    loadComponent: () => import('./pages/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
