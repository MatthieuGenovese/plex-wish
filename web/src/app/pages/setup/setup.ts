import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { APP_NAME } from '../../core/app-name';
import { errorCode, errorMessage } from '../../core/errors';
import { DdnsStatus, DiskView, ScanState, SetupCheck, SetupService, TmdbView, diskFormValue } from '../../core/setup.service';

const MIN_PASSWORD = 10;

type Step = 'checks' | 'admin' | 'disk' | 'domain' | 'tmdb' | 'scan' | 'finish';
const STEPS: { id: Step; label: string }[] = [
  { id: 'checks', label: 'Vérifications' },
  { id: 'admin', label: 'Compte administrateur' },
  { id: 'disk', label: 'Espace disque' },
  { id: 'domain', label: 'Adresse Internet' },
  { id: 'tmdb', label: 'Synopsis en français' },
  { id: 'scan', label: 'Premier scan' },
  { id: 'finish', label: 'Terminer' },
];

/**
 * Page /installation (D1.3). Trois cas :
 * - installation en cours, site ouvert par Internet : « installation en cours », rien d'autre ;
 * - installation terminée, site ouvert par l'adresse locale du NAS : lien vers l'adresse publique ;
 * - installation en cours, réseau local : l'assistant de premier lancement.
 */
@Component({
  selector: 'app-setup',
  imports: [ReactiveFormsModule],
  template: `
    @let s = setup.status();
    <section class="setup" aria-labelledby="setup-title">
      @if (!s) {
        <h1 id="setup-title">Installation</h1>
        <p role="status">Serveur injoignable. Réessayez dans un instant.</p>
      } @else if (s.installed) {
        <h1 id="setup-title">Installation terminée</h1>
        <p>Le site s’ouvre à son adresse Internet, en connexion sécurisée :</p>
        @if (s.publicUrl) { <p class="big-link"><a [href]="s.publicUrl">{{ s.publicUrl }}</a></p> }
        <p class="hint">Cette adresse locale du NAS ne sert qu’à l’installation.</p>
      } @else if (s.entry === 'public') {
        <h1 id="setup-title">Installation en cours</h1>
        <p role="status">Le site sera disponible dès que l’administrateur aura terminé l’installation. Revenez un peu plus tard.</p>
      } @else {
        <h1 id="setup-title">Installation de {{ appName }}</h1>
        <p class="muted">Étape {{ index() + 1 }} sur {{ steps.length }} : {{ steps[index()].label }}</p>
        <ol class="setup-steps" aria-label="Étapes de l’installation">
          @for (st of steps; track st.id; let i = $index) {
            <li [class.done]="i < index()" [attr.aria-current]="i === index() ? 'step' : null">{{ st.label }}</li>
          }
        </ol>

        <div aria-live="polite">
          @if (message(); as m) {
            <div class="alert" [class.alert-error]="m.error" [class.alert-success]="!m.error" role="status"><p>{{ m.text }}</p></div>
          }
        </div>

        @switch (step()) {
          @case ('checks') {
            <h2>Vérifications</h2>
            <p>Le serveur vérifie qu’il peut lire les vidéos, écrire ses fichiers et utiliser ses outils.</p>
            @if (checks(); as list) {
              <ul class="checks">
                @for (c of list; track c.id) {
                  <li>
                    <span class="badge" [class.badge-success]="c.state === 'OK'" [class.badge-warning]="c.state === 'WARN'"
                          [class.badge-danger]="c.state === 'FAIL'">{{ stateLabel(c.state) }}</span>
                    <div><strong>{{ c.label }}</strong> : {{ c.detail }}
                      @if (c.fix) { <p class="hint">{{ c.fix }}</p> }
                    </div>
                  </li>
                }
              </ul>
              @if (failed()) {
                <div class="alert alert-warning" role="status"><p>Un point est à corriger. Vous pouvez continuer et revenir le vérifier ensuite.</p></div>
              }
            } @else {
              <p class="muted" role="status">Vérification…</p>
            }
            <div class="setup-actions">
              <button type="button" class="btn" (click)="loadChecks()" [disabled]="busy()">Revérifier</button>
              <button type="button" class="btn btn-primary" (click)="go('admin')" [disabled]="!checks()">Continuer</button>
            </div>
          }
          @case ('admin') {
            <h2>Compte administrateur</h2>
            @if (!s.adminExists) {
              <p>Choisissez le nom et le mot de passe de l’administrateur du site. Aucun mot de passe n’est fourni par défaut : notez-le en lieu sûr.</p>
              <form [formGroup]="adminForm" (ngSubmit)="createAdmin()" novalidate>
                <div class="field">
                  <label for="a-name">Nom d’utilisateur</label>
                  <input id="a-name" formControlName="username" autocomplete="username" autocapitalize="none" spellcheck="false" />
                  <span class="hint">3 à 50 caractères : lettres, chiffres, . _ -</span>
                </div>
                <div class="field">
                  <label for="a-pass">Mot de passe</label>
                  <input id="a-pass" [type]="showPassword() ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
                  <span class="hint">{{ minPassword }} caractères minimum.</span>
                </div>
                <div class="field">
                  <label for="a-pass2">Mot de passe, encore une fois</label>
                  <input id="a-pass2" [type]="showPassword() ? 'text' : 'password'" formControlName="confirm" autocomplete="new-password" />
                </div>
                <label class="check"><input type="checkbox" [checked]="showPassword()" (change)="showPassword.set(!showPassword())" />Afficher le mot de passe</label>
                <div class="setup-actions">
                  <button type="submit" class="btn btn-primary" [disabled]="busy()">Créer le compte</button>
                </div>
              </form>
            } @else if (!setup.hasToken()) {
              <p>Le compte administrateur existe déjà. Connectez-vous avec lui pour continuer l’installation.</p>
              <form [formGroup]="loginForm" (ngSubmit)="login()" novalidate>
                <div class="field">
                  <label for="l-name">Identifiant</label>
                  <input id="l-name" formControlName="login" autocomplete="username" autocapitalize="none" spellcheck="false" />
                </div>
                <div class="field">
                  <label for="l-pass">Mot de passe</label>
                  <input id="l-pass" type="password" formControlName="password" autocomplete="current-password" />
                </div>
                <div class="setup-actions">
                  <button type="submit" class="btn btn-primary" [disabled]="busy()">Se connecter</button>
                </div>
              </form>
            } @else {
              <p>Compte administrateur prêt.</p>
              <div class="setup-actions"><button type="button" class="btn btn-primary" (click)="go('disk')">Continuer</button></div>
            }
          }
          @case ('disk') {
            <h2>Espace disque</h2>
            @if (disk(); as d) {
              <p>Espace libre mesuré sur le NAS : <strong>{{ gb(d.freeBytes) }} Go</strong> sur {{ gb(d.totalBytes) }} Go.
                Seuils proposés selon cet espace, modifiables plus tard (Administration › Réglages).</p>
              <form [formGroup]="diskForm" (ngSubmit)="saveDisk()" novalidate>
                <div class="form-row form-top">
                  <div class="field">
                    <label for="d-warn">Alerte « espace faible » (Go)</label>
                    <input id="d-warn" type="number" min="1" formControlName="warnGb" inputmode="numeric" />
                  </div>
                  <div class="field">
                    <label for="d-crit">Alerte « critique » (Go)</label>
                    <input id="d-crit" type="number" min="1" formControlName="criticalGb" inputmode="numeric" />
                  </div>
                  <div class="field">
                    <label for="d-cap">Place pour les copies converties (Go)</label>
                    <input id="d-cap" type="number" min="1" formControlName="remuxCapGb" inputmode="numeric" />
                  </div>
                  <div class="field">
                    <label for="d-web">Place pour le lecteur web (Go)</label>
                    <input id="d-web" type="number" min="1" formControlName="webCapGb" inputmode="numeric" />
                  </div>
                </div>
                <p class="hint">Les alertes préviennent l’administrateur quand l’espace libre passe sous ces seuils. Les « copies converties »
                  sont les vidéos AVI et OGM réécrites pour le téléphone ; au-delà de cette place, les plus anciennes sont effacées.</p>
                @if (d.webCache; as w) {
                  <p class="hint" data-testid="web-cache">Lecteur web : épisodes et sous-titres préparés pour le navigateur, dans
                    <code>{{ w.hostPath ?? 'le dossier du projet (web-cache)' }}</code> ({{ gb(w.freeBytes) }} Go libres sur {{ gb(w.totalBytes) }} Go).
                    Pour le mettre sur un autre volume : <code>WEB_CACHE_PATH</code> dans <code>nas.env</code> (guide de déploiement).</p>
                }
                <div class="setup-actions">
                  <button type="submit" class="btn btn-primary" [disabled]="busy()">Enregistrer et continuer</button>
                </div>
              </form>
            } @else {
              <p class="muted" role="status">Mesure…</p>
            }
          }
          @case ('domain') {
            <h2>Adresse Internet</h2>
            <p>Les utilisateurs ouvriront le site à l’adresse <strong>{{ s.publicUrl }}</strong>.</p>
            @if (ddns(); as dn) {
              @if (dn.supported) {
                <p>L’adresse de la box peut changer : le serveur la signale à DuckDNS toutes les 5 minutes. Collez le jeton DuckDNS
                  (affiché sur duckdns.org une fois connecté, de la forme xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).</p>
                @if (dn.configured) {
                  <div class="alert alert-success" role="status"><p>Jeton enregistré{{ dn.ip ? ' : le nom pointe vers ' + dn.ip : '' }}.</p></div>
                }
                <form [formGroup]="ddnsForm" (ngSubmit)="saveDdns()" novalidate>
                  <div class="field">
                    <label for="dd-token">Jeton DuckDNS</label>
                    <input id="dd-token" type="password" formControlName="token" autocomplete="off" spellcheck="false" />
                    <span class="hint">Rangé avec les secrets du serveur, jamais réaffiché.</span>
                  </div>
                  <div class="setup-actions">
                    <button type="button" class="btn" (click)="go('tmdb')">{{ dn.configured ? 'Continuer' : 'Passer' }}</button>
                    <button type="submit" class="btn btn-primary" [disabled]="busy()">Vérifier et enregistrer</button>
                  </div>
                </form>
              } @else {
                <p>Rien à configurer ici pour cette adresse.</p>
                <div class="setup-actions"><button type="button" class="btn btn-primary" (click)="go('tmdb')">Continuer</button></div>
              }
            } @else {
              <p class="muted" role="status">Chargement…</p>
            }
          }
          @case ('tmdb') {
            <h2>Synopsis en français (facultatif)</h2>
            <p>Sans clé TMDB, les fiches des animés gardent leur résumé en anglais (AniList) : tout le reste fonctionne.
              Avec une clé TMDB gratuite (usage non commercial), le serveur récupère le synopsis et le titre en français quand ils existent.</p>
            @if (tmdb()?.configured) {
              <div class="alert alert-success" role="status"><p>Clé TMDB enregistrée.</p></div>
            }
            <form [formGroup]="tmdbForm" (ngSubmit)="saveTmdb()" novalidate>
              <div class="field">
                <label for="t-key">Clé TMDB (« jeton d’accès en lecture » ou clé API)</label>
                <input id="t-key" type="password" formControlName="token" autocomplete="off" spellcheck="false" />
                <span class="hint">Rangée avec les secrets du serveur, jamais réaffichée. Peut aussi être saisie plus tard (Administration › Réglages).</span>
              </div>
              <div class="setup-actions">
                <button type="button" class="btn" (click)="go('scan')">{{ tmdb()?.configured ? 'Continuer' : 'Passer' }}</button>
                <button type="submit" class="btn btn-primary" [disabled]="busy()">Enregistrer</button>
              </div>
            </form>
          }
          @case ('scan') {
            <h2>Premier scan</h2>
            <p>Le scan parcourt le dossier des vidéos et construit la bibliothèque. Les vidéos ne sont jamais modifiées.
              Affiches et informations arrivent ensuite d’elles-mêmes, en tâche de fond.</p>
            @if (scan(); as sc) {
              @if (sc.status === 'RUNNING') {
                <p role="status">Scan en cours…</p>
              } @else if (sc.status === 'SUCCESS') {
                <div class="alert alert-success" role="status"><p>Scan terminé : {{ sc.videos ?? 0 }} vidéos, {{ sc.episodes ?? 0 }} épisodes.</p></div>
              } @else if (sc.status === 'FAILED') {
                <div class="alert alert-error" role="status"><p>Le scan a échoué : voir Administration › Scan après l’installation.</p></div>
              }
            }
            <div class="setup-actions">
              <button type="button" class="btn" (click)="go('finish')">{{ scan()?.status === 'SUCCESS' ? 'Continuer' : 'Passer' }}</button>
              <button type="button" class="btn btn-primary" (click)="startScan()" [disabled]="busy() || scan()?.status === 'RUNNING'">Lancer le scan</button>
            </div>
          }
          @case ('finish') {
            <h2>Terminer</h2>
            <p>À la fin de l’installation, le site s’ouvre à son adresse Internet et cet assistant se ferme définitivement.</p>
            <p>Adresse du site : <strong>{{ s.publicUrl }}</strong></p>
            <div class="setup-actions">
              <button type="button" class="btn btn-primary btn-lg" (click)="finish()" [disabled]="busy()">Terminer l’installation</button>
            </div>
          }
        }
      }
    </section>
  `,
  styles: `
    .setup { max-width: 44rem; margin: var(--space-6, 2rem) auto; padding: 0 var(--space-4, 1rem); }
    .setup-steps { display: flex; flex-wrap: wrap; gap: var(--space-2, 0.5rem); padding: 0; list-style: none; margin: 0 0 var(--space-4, 1rem); }
    .setup-steps li { padding: 0.25rem 0.75rem; border-radius: 999px; background: var(--surface-2); font-size: 0.875rem; }
    .setup-steps li.done { color: var(--muted, inherit); }
    .setup-steps li[aria-current='step'] { background: var(--accent); color: var(--on-accent); font-weight: 600; }
    .checks { list-style: none; padding: 0; display: grid; gap: var(--space-3, 0.75rem); }
    .checks li { display: flex; gap: var(--space-3, 0.75rem); align-items: flex-start; }
    .checks .badge { flex: none; min-width: 6.5rem; justify-content: center; }
    .setup-actions { display: flex; flex-wrap: wrap; gap: var(--space-2, 0.5rem); justify-content: flex-end; margin-top: var(--space-4, 1rem); }
    .big-link { font-size: 1.25rem; word-break: break-all; }
  `,
})
export class SetupPage implements OnDestroy {
  protected readonly setup = inject(SetupService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly appName = APP_NAME;
  protected readonly steps = STEPS;
  protected readonly minPassword = MIN_PASSWORD;
  protected readonly step = signal<Step>('checks');
  protected readonly index = computed(() => STEPS.findIndex((s) => s.id === this.step()));
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);
  protected readonly showPassword = signal(false);
  protected readonly checks = signal<SetupCheck[] | null>(null);
  protected readonly failed = computed(() => (this.checks() ?? []).some((c) => c.state === 'FAIL'));
  protected readonly disk = signal<DiskView | null>(null);
  protected readonly ddns = signal<DdnsStatus | null>(null);
  protected readonly tmdb = signal<TmdbView | null>(null);
  protected readonly scan = signal<ScanState | null>(null);
  private poll: ReturnType<typeof setInterval> | null = null;

  protected readonly adminForm = this.fb.group({ username: ['', Validators.required], password: [''], confirm: [''] });
  protected readonly loginForm = this.fb.group({ login: ['', Validators.required], password: ['', Validators.required] });
  protected readonly diskForm = this.fb.group({ warnGb: [0], criticalGb: [0], remuxCapGb: [0], webCapGb: [0] });
  protected readonly ddnsForm = this.fb.group({ token: [''] });
  protected readonly tmdbForm = this.fb.group({ token: [''] });

  constructor() {
    const s = this.setup.status();
    if (s && !s.installed && s.entry !== 'public') {
      this.loadChecks();
    }
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  protected stateLabel(state: SetupCheck['state']): string {
    return state === 'OK' ? 'OK' : state === 'WARN' ? 'À vérifier' : 'À corriger';
  }

  protected gb(bytes: number): string {
    return bytes < 0 ? '?' : Math.round(bytes / 1e9).toLocaleString('fr-FR');
  }

  loadChecks(): void {
    this.call(this.setup.checks(), (list) => this.checks.set(list));
  }

  go(step: Step): void {
    this.message.set(null);
    this.step.set(step);
    if (step === 'disk') {
      this.call(this.setup.disk(), (d) => {
        this.disk.set(d);
        this.diskForm.setValue(diskFormValue(d.saved ? d.current : d.proposed));
      });
    } else if (step === 'domain') {
      this.call(this.setup.ddns(), (d) => this.ddns.set(d));
    } else if (step === 'tmdb') {
      this.call(this.setup.tmdb(), (t) => this.tmdb.set(t));
    } else if (step === 'scan') {
      this.call(this.setup.scan(), (sc) => this.scanUpdated(sc));
    }
  }

  createAdmin(): void {
    const v = this.adminForm.getRawValue();
    if (!/^[A-Za-z0-9._-]{3,50}$/.test(v.username.trim())) {
      this.fail('Nom d’utilisateur : 3 à 50 caractères, lettres, chiffres, point, tiret ou underscore.');
      return;
    }
    if (v.password.length < MIN_PASSWORD) {
      this.fail(`Le mot de passe doit faire au moins ${MIN_PASSWORD} caractères.`);
      return;
    }
    if (v.password !== v.confirm) {
      this.fail('Les deux mots de passe sont différents.');
      return;
    }
    this.call(this.setup.createAdmin(v.username.trim(), v.password), () => {
      this.adminForm.reset();
      this.go('disk');
    }, (err) => {
      if (errorCode(err) === 'ADMIN_EXISTS') {
        this.setup.refresh().subscribe();
      }
    });
  }

  login(): void {
    const v = this.loginForm.getRawValue();
    this.call(this.setup.login(v.login.trim(), v.password), () => {
      this.loginForm.reset();
      this.go('disk');
    });
  }

  saveDisk(): void {
    const v = this.diskForm.getRawValue();
    this.call(this.setup.setDisk({ warnGb: Number(v.warnGb), criticalGb: Number(v.criticalGb), remuxCapGb: Number(v.remuxCapGb), webCapGb: Number(v.webCapGb) || null }),
      () => this.go('domain'));
  }

  saveDdns(): void {
    const token = this.ddnsForm.getRawValue().token.trim();
    if (!token) {
      this.fail('Collez le jeton DuckDNS, ou passez cette étape.');
      return;
    }
    this.call(this.setup.setDdns(token), (d) => {
      this.ddns.set(d);
      this.ddnsForm.reset();
      this.message.set({ text: 'Jeton vérifié auprès de DuckDNS et enregistré.', error: false });
    });
  }

  saveTmdb(): void {
    const token = this.tmdbForm.getRawValue().token.trim();
    if (!token) {
      this.fail('Collez la clé TMDB, ou passez cette étape.');
      return;
    }
    this.call(this.setup.setTmdb(token), (t) => {
      this.tmdb.set(t);
      this.tmdbForm.reset();
      this.message.set({ text: 'Clé TMDB enregistrée.', error: false });
    });
  }

  startScan(): void {
    this.call(this.setup.startScan(), (sc) => this.scanUpdated(sc));
  }

  finish(): void {
    this.call(this.setup.finish(), () => this.stopPolling());
  }

  private scanUpdated(sc: ScanState): void {
    this.scan.set(sc);
    if (sc.status === 'RUNNING' && !this.poll) {
      this.poll = setInterval(() => this.setup.scan().subscribe({ next: (s) => this.scanUpdated(s), error: () => this.stopPolling() }), 3000);
    } else if (sc.status !== 'RUNNING') {
      this.stopPolling();
    }
  }

  private stopPolling(): void {
    if (this.poll) {
      clearInterval(this.poll);
      this.poll = null;
    }
  }

  private call<T>(obs: Observable<T>, done: (v: T) => void, onError?: (err: unknown) => void): void {
    this.busy.set(true);
    this.message.set(null);
    obs.subscribe({
      next: (v) => {
        this.busy.set(false);
        done(v);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.fail(errorMessage(err));
        onError?.(err);
      },
    });
  }

  private fail(text: string): void {
    this.message.set({ text, error: true });
  }
}
