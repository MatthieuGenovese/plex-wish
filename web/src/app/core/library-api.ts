import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AnimeCast, AnimeDetail, AnimeSummary, EpisodeSummary, Page, PersonDetail } from './api-types';

export interface AnimeQuery {
  sort?: 'title' | 'recent';
  q?: string;
  page?: number;
  size?: number;
}

/** Lecture de la bibliothèque (aucun chemin de fichier dans ces réponses). */
@Injectable({ providedIn: 'root' })
export class LibraryApi {
  private readonly http = inject(HttpClient);

  animes(query: AnimeQuery = {}): Observable<Page<AnimeSummary>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<Page<AnimeSummary>>('/api/anime', { params });
  }

  anime(id: number | string): Observable<AnimeDetail> {
    return this.http.get<AnimeDetail>(`/api/anime/${encodeURIComponent(id)}`);
  }

  episodes(seasonId: number): Observable<EpisodeSummary[]> {
    return this.http.get<EpisodeSummary[]>(`/api/seasons/${seasonId}/episodes`);
  }

  cast(animeId: number | string): Observable<AnimeCast> {
    return this.http.get<AnimeCast>(`/api/anime/${encodeURIComponent(animeId)}/cast`);
  }

  person(id: string): Observable<PersonDetail> {
    return this.http.get<PersonDetail>(`/api/people/${encodeURIComponent(id)}`);
  }
}
