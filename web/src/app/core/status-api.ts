import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ApiStatus {
  status: string;
}

/** Appels typés à l'API : un service par domaine, écrit à la main (voir ARCHITECTURE §9). */
@Injectable({ providedIn: 'root' })
export class StatusApi {
  private readonly http = inject(HttpClient);

  get(): Observable<ApiStatus> {
    return this.http.get<ApiStatus>('/api/status');
  }
}
