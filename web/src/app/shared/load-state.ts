import { Signal, computed, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, of, startWith, switchMap } from 'rxjs';
import { errorMessage } from '../core/errors';

export interface LoadState<T> {
  loading: boolean;
  data: T | null;
  error: string | null;
  status: number | null;
}

/**
 * Charge des données à chaque changement de `params` (requête précédente annulée), avec état de
 * chargement et message d'erreur lisible. Les données précédentes restent affichées pendant le chargement.
 * À appeler dans un contexte d'injection (constructeur ou initialiseur de champ).
 */
export function loadOn<P, T>(params: Signal<P>, fetch: (p: P) => Observable<T>): Signal<LoadState<T>> & { reload(): void } {
  const reloads = signal(0);
  const trigger = computed(() => ({ p: params(), n: reloads() }));
  let last: T | null = null;
  const state = toSignal(
    toObservable(trigger).pipe(
      switchMap(({ p }) =>
        fetch(p).pipe(
          map((data): LoadState<T> => {
            last = data;
            return { loading: false, data, error: null, status: null };
          }),
          catchError((err: unknown) =>
            of<LoadState<T>>({
              loading: false,
              data: null,
              error: errorMessage(err),
              status: err instanceof HttpErrorResponse ? err.status : null,
            }),
          ),
          startWith<LoadState<T>>({ loading: true, data: last, error: null, status: null }),
        ),
      ),
    ),
    { initialValue: { loading: true, data: null, error: null, status: null } },
  );
  return Object.assign(state, { reload: () => reloads.update((n) => n + 1) });
}
