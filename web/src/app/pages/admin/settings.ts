import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { errorMessage } from '../../core/errors';
import { DdnsStatus, DiskThresholds, DiskView, TmdbView } from '../../core/setup.service';
import { formatDateTime } from '../../shared/format';
import { loadOn } from '../../shared/load-state';

interface Overview {
  version: string;
  publicUrl: string | null;
  tmdb: TmdbView;
  ddns: DdnsStatus;
  disk: DiskView;
}

/**
 * Réglages de l'installation (D1.3) : clé TMDB, jeton du DNS dynamique, seuils d'espace disque. Les secrets ne sont
 * jamais réaffichés : seulement « enregistré » ou non.
 */
@Component({
  selector: 'app-admin-settings',
  imports: [ReactiveFormsModule],
  template: `
    <div aria-live="polite">
      @if (message(); as m) {
        <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
      }
    </div>
    @let o = overview();
    @if (o.error) {
      <div class="alert alert-error" role="alert"><p>{{ o.error }}</p></div>
    } @else if (o.data; as v) {
      <p class="muted">Version {{ v.version }} · adresse du site : {{ v.publicUrl ?? '—' }}</p>

      <section class="card" aria-labelledby="s-tmdb">
        <h2 id="s-tmdb">Synopsis en français (TMDB)</h2>
        @if (v.tmdb.configured) {
          <p>Clé enregistrée{{ v.tmdb.source === 'configuration' ? ' (dans la configuration du serveur)' : '' }}.
            Les synopsis et titres en français arrivent en tâche de fond.</p>
        } @else {
          <p>Pas de clé : les résumés restent en anglais (AniList), tout le reste fonctionne. Une clé TMDB gratuite
            (usage non commercial) donne le synopsis et le titre en français quand ils existent.</p>
        }
        <form [formGroup]="tmdbForm" (ngSubmit)="saveTmdb()" novalidate>
          <div class="field">
            <label for="st-key">{{ v.tmdb.configured ? 'Remplacer la clé' : 'Clé TMDB' }} (« jeton d’accès en lecture » ou clé API)</label>
            <input id="st-key" type="password" formControlName="token" autocomplete="off" spellcheck="false" />
            <span class="hint">Rangée avec les secrets du serveur (fichier lisible par lui seul), jamais réaffichée.</span>
          </div>
          <div class="actions">
            <button type="submit" class="btn btn-primary" [disabled]="busy()">Enregistrer</button>
            @if (v.tmdb.source === 'interface') {
              <button type="button" class="btn btn-danger" (click)="clearTmdb()" [disabled]="busy()">Retirer la clé</button>
            }
          </div>
        </form>
      </section>

      <section class="card" aria-labelledby="s-ddns">
        <h2 id="s-ddns">Nom de domaine dynamique</h2>
        @if (!v.ddns.supported) {
          <p>L’adresse du site ({{ v.ddns.domain ?? '—' }}) n’est pas un nom DuckDNS : rien à mettre à jour ici.</p>
        } @else {
          <p>{{ v.ddns.domain }} :
            @if (!v.ddns.configured) { pas de jeton, l’adresse n’est pas mise à jour automatiquement. }
            @else if (v.ddns.lastOk === true) { à jour{{ v.ddns.ip ? ', pointe vers ' + v.ddns.ip : '' }} ({{ date(v.ddns.lastAttempt) }}). }
            @else if (v.ddns.lastOk === false) { échec de la dernière mise à jour ({{ v.ddns.message }}). }
            @else { jeton enregistré. }
          </p>
          <form [formGroup]="ddnsForm" (ngSubmit)="saveDdns()" novalidate>
            <div class="field">
              <label for="sd-token">{{ v.ddns.configured ? 'Remplacer le jeton DuckDNS' : 'Jeton DuckDNS' }}</label>
              <input id="sd-token" type="password" formControlName="token" autocomplete="off" spellcheck="false" />
              <span class="hint">Vérifié auprès de DuckDNS avant d’être enregistré, jamais réaffiché.</span>
            </div>
            <div class="actions">
              <button type="submit" class="btn btn-primary" [disabled]="busy()">Vérifier et enregistrer</button>
              @if (v.ddns.configured) {
                <button type="button" class="btn btn-danger" (click)="clearDdns()" [disabled]="busy()">Retirer le jeton</button>
              }
            </div>
          </form>
        }
      </section>

      <section class="card" aria-labelledby="s-disk">
        <h2 id="s-disk">Espace disque</h2>
        <p>{{ gb(v.disk.freeBytes) }} Go libres sur {{ gb(v.disk.totalBytes) }} Go.
          Proposition pour cet espace libre : alerte à {{ v.disk.proposed.warnGb }} Go, critique à {{ v.disk.proposed.criticalGb }} Go,
          {{ v.disk.proposed.remuxCapGb }} Go pour les copies converties.</p>
        <form [formGroup]="diskForm" (ngSubmit)="saveDisk()" novalidate>
          <div class="form-row form-top">
            <div class="field">
              <label for="sk-warn">Alerte « espace faible » (Go)</label>
              <input id="sk-warn" type="number" min="1" formControlName="warnGb" inputmode="numeric" />
            </div>
            <div class="field">
              <label for="sk-crit">Alerte « critique » (Go)</label>
              <input id="sk-crit" type="number" min="1" formControlName="criticalGb" inputmode="numeric" />
            </div>
            <div class="field">
              <label for="sk-cap">Place pour les copies converties (Go)</label>
              <input id="sk-cap" type="number" min="1" formControlName="remuxCapGb" inputmode="numeric" />
            </div>
          </div>
          <div class="actions">
            <button type="button" class="btn" (click)="diskForm.setValue(v.disk.proposed)">Reprendre la proposition</button>
            <button type="submit" class="btn btn-primary" [disabled]="busy()">Enregistrer</button>
          </div>
        </form>
      </section>
    } @else {
      <p class="muted" role="status">Chargement…</p>
    }
  `,
  styles: `
    .card { margin-bottom: var(--space-4, 1rem); }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2, 0.5rem); }
  `,
})
export class SettingsPage {
  private readonly http = inject(HttpClient);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly date = formatDateTime;
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly tmdbForm = this.fb.group({ token: [''] });
  protected readonly ddnsForm = this.fb.group({ token: [''] });
  protected readonly diskForm = this.fb.group({ warnGb: [0], criticalGb: [0], remuxCapGb: [0] });
  protected readonly overview = loadOn(signal(null), () => this.http.get<Overview>('/api/admin/settings'));
  private diskFilled = false;

  constructor() {
    // Formulaire de l'espace disque rempli une fois, avec les valeurs en vigueur.
    effect(() => {
      const d = this.overview().data?.disk;
      if (d && !this.diskFilled) {
        untracked(() => this.diskForm.setValue(d.current));
        this.diskFilled = true;
      }
    });
  }

  protected gb(bytes: number): string {
    return bytes < 0 ? '?' : Math.round(bytes / 1e9).toLocaleString('fr-FR');
  }

  saveTmdb(): void {
    const token = this.tmdbForm.getRawValue().token.trim();
    if (!token) return this.fail('Collez la clé TMDB.');
    this.call(this.http.put<TmdbView>('/api/admin/settings/tmdb', { token }), 'Clé TMDB enregistrée.', () => this.tmdbForm.reset());
  }

  clearTmdb(): void {
    this.call(this.http.delete<TmdbView>('/api/admin/settings/tmdb'), 'Clé TMDB retirée : synopsis en anglais pour les nouvelles fiches.');
  }

  saveDdns(): void {
    const token = this.ddnsForm.getRawValue().token.trim();
    if (!token) return this.fail('Collez le jeton DuckDNS.');
    this.call(this.http.put<DdnsStatus>('/api/admin/settings/ddns', { token }), 'Jeton vérifié auprès de DuckDNS et enregistré.',
      () => this.ddnsForm.reset());
  }

  clearDdns(): void {
    this.call(this.http.delete<DdnsStatus>('/api/admin/settings/ddns'), 'Jeton retiré : l’adresse n’est plus mise à jour automatiquement.');
  }

  saveDisk(): void {
    const v = this.diskForm.getRawValue();
    const t: DiskThresholds = { warnGb: Number(v.warnGb), criticalGb: Number(v.criticalGb), remuxCapGb: Number(v.remuxCapGb) };
    this.call(this.http.put<DiskView>('/api/admin/settings/disk', t), 'Seuils d’espace disque enregistrés.');
  }

  private call<T>(obs: Observable<T>, ok: string, done?: () => void): void {
    this.busy.set(true);
    this.message.set(null);
    obs.subscribe({
      next: () => {
        this.busy.set(false);
        this.message.set({ text: ok, error: false });
        done?.();
        this.overview.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.fail(errorMessage(err));
      },
    });
  }

  private fail(text: string): void {
    this.message.set({ text, error: true });
  }
}
