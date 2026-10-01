import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Ajoute le token d'accès aux appels /api et, sur un 401, rafraîchit la session puis rejoue la requête
 * une seule fois. Plusieurs 401 simultanés partagent le même refresh (AuthService.refresh).
 * Si le refresh échoue (session expirée), retour à la page de connexion.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || req.url.startsWith('/api/auth/')) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  const sentWith = auth.accessToken();

  return next(withToken(req, sentWith)).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401) {
        return throwError(() => err);
      }
      // Un autre appel a déjà obtenu un nouveau token pendant ce temps : on rejoue directement avec lui.
      const current = auth.accessToken();
      if (current && current !== sentWith) {
        return next(withToken(req, current));
      }
      return auth.refresh().pipe(
        catchError((refreshErr: unknown) => {
          if (refreshErr instanceof HttpErrorResponse && refreshErr.status === 401) {
            const returnUrl = router.routerState.snapshot.url;
            router.navigate(['/login'], { queryParams: returnUrl && returnUrl !== '/' ? { returnUrl } : {} });
          }
          return throwError(() => err);
        }),
        switchMap((token) => next(withToken(req, token))),
      );
    }),
  );
};

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}
