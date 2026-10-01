import { Component, ElementRef, computed, effect, inject, input, numberAttribute, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AdminApi } from '../../core/admin-api';
import { errorCode, errorMessage } from '../../core/errors';
import { openModal } from '../../shared/dialog';
import { formatDateTime, formatDuration, formatNumber } from '../../shared/format';
import { loadOn } from '../../shared/load-state';
import { Pager } from '../../shared/pager';
import { ScanStatusBadge } from './scan-status';

/** Pendant un scan, l'état est relu à cet intervalle. */
export const POLL_MS = 2000;

@Component({
  selector: 'app-admin-scan',
  imports: [RouterLink, Pager, ScanStatusBadge],
  template: `
    <section class="card" aria-labelledby="scan-title">
      <div class="page-header">
        <h2 id="scan-title">Scan de la bibliothèque</h2>
        <button type="button" class="btn-primary" (click)="start(false)" [disabled]="running() || starting()">
          {{ running() ? 'Scan en cours…' : 'Lancer un scan' }}
        </button>
      </div>
      <p class="muted">Le scan lit le dossier média (en lecture seule), reconnaît animés, saisons et épisodes, puis met la base à jour. Il ne modifie jamais les fichiers.</p>

      <div aria-live="polite">
        @if (message(); as m) {
          <div class="alert" [class.alert-error]="m.kind === 'error'" [class.alert-warning]="m.kind === 'warning'" role="status">
            <p>{{ m.text }}</p>
          </div>
        }
      </div>

      @let l = latest();
      @if (l.data; as r) {
        <h3>Dernier scan <span class="muted">n° {{ r.id }}</span> <app-scan-status [status]="r.status" /></h3>
        <dl class="facts">
          <div><dt>Lancé</dt><dd>{{ date(r.startedAt) }} par {{ r.triggeredBy }}</dd></div>
          @if (r.finishedAt) {
            <div><dt>Durée</dt><dd>{{ duration(r.stats?.durationMs) }}</dd></div>
          }
          @if (r.stats; as s) {
            <div><dt>Vidéos</dt><dd>{{ num(s.videos) }}</dd></div>
            <div><dt>Épisodes reconnus</dt><dd>{{ num(s.episodes) }}</dd></div>
            <div><dt>Nouveaux fichiers</dt><dd>{{ num(s.newFiles) }}</dd></div>
            <div><dt>Disparus</dt><dd>{{ num(s.missing) }}</dd></div>
          }
        </dl>

        @if (r.status === 'RUNNING') {
          <p class="muted" role="status">Scan en cours depuis {{ date(r.startedAt) }}. Cette page se met à jour toute seule.</p>
        } @else if (r.status === 'FAILED' && r.failureCode === 'MASS_REMOVAL') {
          <div class="alert alert-warning" role="alert">
            <p><strong>Scan arrêté par sécurité : rien n’a été modifié.</strong></p>
            <p>{{ r.failureReason }}</p>
            <p>Vérifiez d’abord que le bon dossier est monté (partage NAS accessible, bon <code>MEDIA_PATH</code>). Si ces fichiers ont vraiment été retirés, vous pouvez relancer en le confirmant.</p>
            <button type="button" class="btn-danger" (click)="askConfirmation()">Relancer en confirmant la disparition…</button>
          </div>
        } @else if (r.status === 'FAILED') {
          <div class="alert alert-error" role="alert">
            <p><strong>Le scan a échoué : rien n’a été marqué comme disparu.</strong></p>
            <p>{{ r.failureReason }}</p>
          </div>
        } @else {
          <p><a [routerLink]="['/admin/report']" [queryParams]="{ scan: r.id }">Voir le rapport de ce scan</a></p>
        }
      } @else if (l.status === 404) {
        <p class="alert">Aucun scan n’a encore été lancé.</p>
      } @else if (l.error) {
        <div class="alert alert-error" role="alert"><p>{{ l.error }}</p></div>
      } @else {
        <p class="muted" role="status">Chargement…</p>
      }
    </section>

    <section aria-labelledby="history-title">
      <h2 id="history-title">Historique</h2>
      @let h = history();
      @if (h.error) {
        <div class="alert alert-error" role="alert"><p>{{ h.error }}</p></div>
      } @else if (h.data; as page) {
        @if (page.total === 0) {
          <p class="muted">Aucun scan.</p>
        } @else {
          <div class="table-wrap">
            <table class="wide">
              <thead>
                <tr>
                  <th scope="col">N°</th><th scope="col">Statut</th><th scope="col">Lancé</th><th scope="col">Par</th>
                  <th scope="col">Durée</th><th scope="col">Vidéos</th><th scope="col">Épisodes</th>
                  <th scope="col">Nouveaux</th><th scope="col">Disparus</th><th scope="col">Problèmes</th><th scope="col">Rapport</th>
                </tr>
              </thead>
              <tbody>
                @for (r of page.items; track r.id) {
                  <tr>
                    <td>{{ r.id }}</td>
                    <td><app-scan-status [status]="r.status" />
                      @if (r.failureReason) { <div class="muted reason">{{ r.failureReason }}</div> }
                    </td>
                    <td>{{ date(r.startedAt) }}</td>
                    <td>{{ r.triggeredBy }}</td>
                    <td>{{ duration(r.stats?.durationMs) }}</td>
                    <td>{{ num(r.stats?.videos) }}</td>
                    <td>{{ num(r.stats?.episodes) }}</td>
                    <td>{{ num(r.stats?.newFiles) }}</td>
                    <td>{{ num(r.stats?.missing) }}</td>
                    <td>{{ num(issues(r.issueCounts)) }}</td>
                    <td>
                      @if (r.status === 'SUCCESS') {
                        <a [routerLink]="['/admin/report']" [queryParams]="{ scan: r.id }" [attr.aria-label]="'Rapport du scan ' + r.id">Voir</a>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [total]="page.total" [size]="page.size" label="Pages de l’historique"
                     (pageChange)="goToPage($event)" />
        }
      }
    </section>

    <dialog #confirmDialog aria-labelledby="confirm-title" (close)="onDialogClose()">
      <h2 id="confirm-title">Confirmer la disparition massive ?</h2>
      <p>Plus de la moitié des fichiers connus seront marqués <strong>indisponibles</strong> : leurs épisodes disparaîtront de la bibliothèque pour tous les utilisateurs.</p>
      <p>Les fichiers ne sont pas supprimés et rien n’est perdu : s’ils reviennent, un nouveau scan les fera réapparaître. Mais si le partage NAS est simplement absent, mieux vaut d’abord le remettre.</p>
      <form method="dialog" class="dialog-actions">
        <button value="cancel" autofocus>Annuler</button>
        <button value="confirm" class="btn-danger">Confirmer et relancer</button>
      </form>
    </dialog>
  `,
  styles: `
    .facts { display: flex; flex-wrap: wrap; gap: var(--space-3) var(--space-6); margin: 0 0 var(--space-4); }
    .facts dt { color: var(--color-text-muted); font-size: var(--font-size-sm); }
    .facts dd { margin: 0; font-weight: var(--font-weight-medium); }
    .reason { max-width: 24rem; font-size: var(--font-size-xs); }
  `,
})
export class ScanPage {
  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  private readonly confirmDialog = viewChild.required<ElementRef<HTMLDialogElement>>('confirmDialog');

  /** ?page= (base 1) de l'historique. */
  readonly page = input(1, { transform: (v: unknown) => Math.max(1, numberAttribute(v, 1)) });

  private readonly none = signal(null);
  protected readonly latest = loadOn(this.none, () => this.api.scanReport());
  protected readonly history = loadOn(this.page, (p) => this.api.scans(p - 1));
  protected readonly running = computed(() => this.latest().data?.status === 'RUNNING');
  protected readonly starting = signal(false);
  protected readonly message = signal<{ kind: 'info' | 'warning' | 'error'; text: string } | null>(null);

  protected readonly date = formatDateTime;
  protected readonly duration = formatDuration;
  protected readonly num = formatNumber;

  constructor() {
    // Pendant un scan : relire l'état régulièrement ; à la fin, rafraîchir l'historique.
    let wasRunning = false;
    effect((onCleanup) => {
      const state = this.latest();
      const running = state.data?.status === 'RUNNING';
      if (running) {
        const timer = setTimeout(() => this.latest.reload(), POLL_MS);
        onCleanup(() => clearTimeout(timer));
      } else if (wasRunning && !state.loading) {
        this.history.reload();
        this.message.set(null); // « Scan lancé » n'a plus de sens : le résultat s'affiche
      }
      if (!state.loading) {
        wasRunning = running;
      }
    });
  }

  start(confirmMassRemoval: boolean): void {
    this.starting.set(true);
    this.message.set(null);
    this.api.startScan(confirmMassRemoval).subscribe({
      next: ({ scanId }) => {
        this.starting.set(false);
        this.message.set({ kind: 'info', text: `Scan n° ${scanId} lancé.` });
        this.latest.reload();
        this.history.reload();
      },
      error: (err: unknown) => {
        this.starting.set(false);
        if (errorCode(err) === 'SCAN_ALREADY_RUNNING') {
          this.message.set({
            kind: 'warning',
            text: 'Un scan est déjà en cours : un seul à la fois. Son état s’affiche ci-dessous et se met à jour tout seul.',
          });
          this.latest.reload();
        } else {
          this.message.set({ kind: 'error', text: errorMessage(err, 'Impossible de lancer le scan.') });
        }
      },
    });
  }

  askConfirmation(): void {
    openModal(this.confirmDialog().nativeElement);
  }

  onDialogClose(): void {
    const dialog = this.confirmDialog().nativeElement;
    if (dialog.returnValue === 'confirm') {
      this.start(true);
    }
    dialog.returnValue = '';
  }

  goToPage(page: number): void {
    this.router.navigate([], { queryParams: { page: page === 0 ? null : page + 1 }, queryParamsHandling: 'merge' });
  }

  issues(counts: Record<string, number>): number {
    return Object.values(counts).reduce((a, b) => a + b, 0);
  }
}
