import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ThemePreference, ThemeService } from '../core/theme.service';
import { Icon } from './icon';
import { IconName } from './icons';

/** Choix du thème (Système / Sombre / Clair), enregistré sur cet appareil. */
@Component({
  selector: 'app-theme-switch',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="segmented theme-switch" role="group" aria-label="Thème">
      @for (o of options; track o.value) {
        <button type="button" [attr.aria-pressed]="theme.preference() === o.value" (click)="theme.set(o.value)">
          <app-icon [name]="o.icon" size="sm" />{{ o.label }}
        </button>
      }
    </div>
  `,
})
export class ThemeSwitch {
  protected readonly theme = inject(ThemeService);
  protected readonly options: { value: ThemePreference; label: string; icon: IconName }[] = [
    { value: 'system', label: 'Système', icon: 'contrast' },
    { value: 'dark', label: 'Sombre', icon: 'dark_mode' },
    { value: 'light', label: 'Clair', icon: 'light_mode' },
  ];
}
