import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Override } from '../../core/api-types';
import { AdminApi } from '../../core/admin-api';
import { errorMessage } from '../../core/errors';
import { formatDateTime } from '../../shared/format';
import { loadOn } from '../../shared/load-state';

const ACTIONS = { EPISODE: 'Épisode', EXTRA: 'Extra', IGNORE: 'Ignoré' } as const;

/** Corrections manuelles enregistrées : jamais écrasées par un scan, annulables ici. */
@Component({
  selector: 'app-admin-overrides',
  imports: [RouterLink],
  template: `
    <h2>Corrections manuelles</h2>
    <p class="muted">Une correction s’applique au scan suivant et n’est jamais écrasée. Pour en ajouter une : <a routerLink="/admin/report" [queryParams]="{ categorie: 'UNRESOLVED' }">fichiers non résolus</a>.</p>
    <div aria-live="polite">
      @if (message(); as m) {
        <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status">
          <p>{{ m.text }} @if (!m.error) { <a routerLink="/admin/scan">Relancer un scan</a> }</p>
        </div>
      }
    </div>
    @let l = list();
    @if (l.error) {
      <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
    } @else if (l.data; as items) {
      @if (items.length === 0) {
        <p class="alert">Aucune correction.</p>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Fichier</th><th scope="col">Chemin</th><th scope="col">Correction</th>
                <th scope="col">Par</th><th scope="col">Le</th><th scope="col"><span class="visually-hidden">Action</span></th>
              </tr>
            </thead>
            <tbody>
              @for (o of items; track o.relativePath) {
                <tr>
                  <td class="mono">{{ o.mediaFileId ?? '—' }}</td>
                  <td class="path">{{ o.relativePath }}</td>
                  <td>{{ describe(o) }}</td>
                  <td>{{ o.createdBy }}</td>
                  <td>{{ date(o.createdAt) }}</td>
                  <td>
                    @if (o.mediaFileId !== null) {
                      <button type="button" class="btn-small" (click)="remove(o)" [disabled]="busy()"
                              [attr.aria-label]="'Annuler la correction du fichier ' + o.mediaFileId">Annuler</button>
                    } @else {
                      <span class="muted">fichier absent</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
})
export class OverridesPage {
  private readonly api = inject(AdminApi);
  protected readonly list = loadOn(signal(null), () => this.api.overrides());
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly date = formatDateTime;

  describe(o: Override): string {
    return o.action === 'EPISODE'
      ? `${o.animeTitle} · saison ${o.seasonNumber} · épisode ${o.episodeNumber}`
      : ACTIONS[o.action];
  }

  remove(o: Override): void {
    this.busy.set(true);
    this.api.deleteOverride(o.mediaFileId!).subscribe({
      next: () => {
        this.busy.set(false);
        this.message.set({ text: `Correction du fichier n° ${o.mediaFileId} annulée : effet au prochain scan.`, error: false });
        this.list.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.message.set({ text: errorMessage(err), error: true });
      },
    });
  }
}
