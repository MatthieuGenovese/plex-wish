import { Component, input } from '@angular/core';
import { ScanStatus } from '../../core/api-types';

const LABELS: Record<ScanStatus, string> = { RUNNING: 'En cours', SUCCESS: 'Terminé', FAILED: 'Échec' };

@Component({
  selector: 'app-scan-status',
  template: `<span class="badge" [class.badge-success]="status() === 'SUCCESS'" [class.badge-danger]="status() === 'FAILED'"
                   [class.badge-warning]="status() === 'RUNNING'">{{ label() }}</span>`,
})
export class ScanStatusBadge {
  readonly status = input.required<ScanStatus>();
  protected label = () => LABELS[this.status()];
}
