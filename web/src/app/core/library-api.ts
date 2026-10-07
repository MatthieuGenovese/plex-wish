import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AnimeCast, AnimeDetail, AnimeSummary, ContinueWatching, EpisodeSummary, GenreCount, Page, PersonDetail, Progress } from './api-types';

export type AnimeSort = 'title' | 'recent' | 'year';
export type WatchFilter = 'unseen' | 'inProgress' | 'seen';

/** Paramètres de GET /api/anime (ARCHITECTURE §8 et §24.2). */
export interface AnimeQuery {
  sort?: AnimeSort;
  q?: string;
  page?: number;
  size?: number;
  yearFrom?: number;
  yearTo?: number;
  watch?: WatchFilter;
  browser?: boolean;
  genre?: string;
}

/** Lecture de la bibliothèque (aucun chemin de fichier dans ces réponses). */
@Injectable({ providedIn: 'root' })
export class LibraryApi {
  private readonly http = inject(HttpClient);

  animes(query: AnimeQuery = {}): Observable<Page<AnimeSummary>> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
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

  /** « Continuer à regarder » : une entrée par animé, épisode à reprendre ou suivant (§24.1). */
  continueWatching(limit = 20): Observable<ContinueWatching[]> {
    return this.http.get<ContinueWatching[]>('/api/me/continue-watching', { params: { limit } });
  }

  /** Progression de l'utilisateur sur un animé (épisodes vus ou commencés). */
  progress(animeId: number | string): Observable<Progress[]> {
    return this.http.get<Progress[]>('/api/me/progress', { params: { animeId: String(animeId) } });
  }

  /** Genres présents dans la bibliothèque, libellés français (§24.6). */
  genres(): Observable<GenreCount[]> {
    return this.http.get<GenreCount[]>('/api/genres');
  }

  person(id: string): Observable<PersonDetail> {
    return this.http.get<PersonDetail>(`/api/people/${encodeURIComponent(id)}`);
  }
}
