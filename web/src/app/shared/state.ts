import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icon } from './icon';
import { IconName } from './icons';

/**
 * État vide ou d'erreur : icône, titre, explication, et une action (contenu projeté : bouton ou lien).
 * {@code role="alert"} pour une erreur, sinon simple texte.
 */
@Component({
  selector: 'app-state',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="state" [class.state-error]="error()" [attr.role]="error() ? 'alert' : null">
      <app-icon [name]="icon()" size="lg" />
      <p class="state-title">{{ heading() }}</p>
      @if (message()) {
        <p class="state-text">{{ message() }}</p>
      }
      <div class="state-actions"><ng-content /></div>
    </div>
  `,
})
export class StateBox {
  readonly icon = input<IconName>('info');
  readonly heading = input.required<string>();
  readonly message = input<string | null>(null);
  readonly error = input(false);
}
