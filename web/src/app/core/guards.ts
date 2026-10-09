import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { SetupService } from './setup.service';

// La session est rétablie avant la première navigation (provideAppInitializer) : les gardes sont synchrones.

/** Pages réservées aux utilisateurs connectés ; sinon /login, avec retour à la page demandée. */
export const authGuard: CanActivateFn = (_route, state) =>
  inject(AuthService).isLoggedIn() ||
  inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });

/** Administration : rôle ADMIN obligatoire. Un utilisateur normal est renvoyé à l'accueil. */
export const adminGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn()) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return auth.isAdmin() || router.createUrlTree(['/']);
};

/** Page de connexion : inutile si déjà connecté. */
export const guestGuard: CanActivateFn = () =>
  !inject(AuthService).isLoggedIn() || inject(Router).createUrlTree(['/']);

/** Installation non terminée, ou site ouvert par l'adresse locale du NAS : la page d'installation remplace tout. */
export const setupGuard: CanActivateFn = () =>
  !inject(SetupService).takesOver() || inject(Router).createUrlTree(['/installation']);

/** La page d'installation n'a plus lieu d'être une fois l'installation terminée (adresse publique). */
export const installationGuard: CanActivateFn = () =>
  inject(SetupService).takesOver() || inject(Router).createUrlTree(['/']);
