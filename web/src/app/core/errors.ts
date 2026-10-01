import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './api-types';

/** Code d'erreur de l'API (« SCAN_ALREADY_RUNNING »…), ou null si la réponse n'en contient pas. */
export function errorCode(err: unknown): string | null {
  if (err instanceof HttpErrorResponse && err.error && typeof err.error === 'object') {
    return (err.error as Partial<ApiError>).error ?? null;
  }
  return null;
}

/** Message lisible pour l'utilisateur, quelle que soit l'erreur. */
export function errorMessage(err: unknown, fallback = 'Une erreur est survenue.'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'Serveur injoignable. Vérifiez votre connexion, puis réessayez.';
    }
    const body = err.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && typeof body.message === 'string') {
      return body.message;
    }
    if (err.status >= 500) {
      return 'Le serveur a rencontré un problème. Réessayez dans un instant.';
    }
  }
  return fallback;
}
