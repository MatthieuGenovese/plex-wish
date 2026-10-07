import { ChangeDetectionStrategy, Component, ElementRef, HostListener, inject, output, signal, viewChild } from '@angular/core';
import { NavigationStart, Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Icon } from './icon';
import { ThemeSwitch } from './theme-switch';

/**
 * Menu Compte (bureau) : nom, thème, Mon compte, Administration (admins), À propos, Se déconnecter.
 * Ouverture au clic ou au clavier ; Échap, un clic à côté ou une navigation le ferment (focus rendu au bouton).
 */
@Component({
  selector: 'app-account-menu',
  imports: [RouterLink, Icon, ThemeSwitch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (auth.user(); as user) {
      <button #trigger type="button" class="avatar-btn" [attr.aria-expanded]="open()" aria-controls="account-panel"
              [attr.aria-label]="'Compte : ' + user.username" (click)="toggle()">{{ user.username[0].toUpperCase() }}</button>
      @if (open()) {
        <div id="account-panel" class="menu-panel" #panel>
          <div class="menu-who"><strong>{{ user.username }}</strong><span>{{ user.role === 'ADMIN' ? 'Administrateur' : 'Utilisateur' }}</span></div>
          <p class="menu-label" id="menu-theme">Thème</p>
          <app-theme-switch />
          <ul class="menu-list">
            <li><a routerLink="/compte"><app-icon name="account_circle" />Mon compte</a></li>
            @if (auth.isAdmin()) {
              <li><a routerLink="/admin"><app-icon name="admin_panel_settings" />Administration</a></li>
            }
            <li><a routerLink="/a-propos"><app-icon name="info" />À propos</a></li>
            <li><button type="button" (click)="logout.emit()"><app-icon name="logout" />Se déconnecter</button></li>
          </ul>
        </div>
      }
    }
  `,
  host: { class: 'account-menu' },
})
export class AccountMenu {
  protected readonly auth = inject(AuthService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  readonly logout = output<void>();
  protected readonly open = signal(false);

  constructor() {
    inject(Router).events.subscribe((e) => {
      if (e instanceof NavigationStart) this.open.set(false);
    });
  }

  toggle(): void {
    this.open.update((o) => !o);
    if (this.open()) {
      // Premier élément du menu focalisé (clavier) une fois affiché.
      queueMicrotask(() => (this.host.nativeElement as HTMLElement).querySelector<HTMLElement>('.menu-panel button, .menu-panel a')?.focus());
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) {
      this.open.set(false);
      this.trigger()?.nativeElement.focus();
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (this.open() && !(this.host.nativeElement as HTMLElement).contains(event.target as Node)) {
      this.open.set(false);
    }
  }
}
