import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from './auth.service';

/** Réponse de GET /api/episodes/{id}/web-playback (docs/WEB-PLAYER.md). */
export interface WebAudioTrack {
  id: number;
  label: string;
  language: string | null;
  codec: string;
  isDefault: boolean;
}

export interface WebSubtitleTrack {
  id: number;
  label: string;
  language: string | null;
  forced: boolean;
  isDefault: boolean;
  /** « ass » : rendu par JASSUB (url = ASS, vttUrl = secours) ; « vtt » : piste WebVTT native. */
  format: 'ass' | 'vtt';
  url: string;
  vttUrl: string | null;
}

export interface WebPreparing {
  phase: string;
  position: number;
  progress: number | null;
  estimatedSeconds: number;
  retryAfterSeconds: number;
  message: string;
}

export interface WebEpisodeInfo {
  id: number;
  animeId: number;
  animeTitle: string;
  seasonNumber: number;
  episodeNumber: number;
  title: string | null;
  durationSeconds: number | null;
}

export interface WebPlayback {
  state: 'READY' | 'PREPARING' | 'UNSUPPORTED';
  mode: 'DIRECT' | 'HLS' | null;
  url: string | null;
  mimeType: string | null;
  expiresAt: string | null;
  growing: boolean;
  durationSeconds: number | null;
  audio: WebAudioTrack[];
  subtitles: WebSubtitleTrack[];
  fonts: string[];
  imageSubtitles: boolean;
  unavailableAudio: string[];
  preparing: WebPreparing | null;
  reason: string | null;
  episode: WebEpisodeInfo;
  next: { id: number; seasonNumber: number; episodeNumber: number; title: string | null } | null;
  resume: { positionSeconds: number; durationSeconds: number; completed: boolean } | null;
  subtitleOffsetSeconds: number;
}

/** Lecture dans le navigateur : quoi lire, et enregistrement de la position (même API que l'app Android). */
@Injectable({ providedIn: 'root' })
export class PlaybackApi {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** 200 (prêt ou non lisible) ou 202 (préparation en cours) : le corps a le même format. */
  webPlayback(episodeId: number, caps: string[]): Observable<HttpResponse<WebPlayback>> {
    return this.http.get<WebPlayback>(`/api/episodes/${episodeId}/web-playback`, {
      params: { caps: caps.join(',') },
      observe: 'response',
    });
  }

  saveProgress(episodeId: number, positionSeconds: number, durationSeconds: number): Observable<unknown> {
    return this.http.put(`/api/episodes/${episodeId}/progress`, { positionSeconds, durationSeconds });
  }

  /**
   * Dernière position à la fermeture de la page (onglet fermé, navigation) : `fetch` avec `keepalive`, qui survit à
   * la page. Jeton d'accès en mémoire seulement (jamais stocké).
   */
  saveProgressOnExit(episodeId: number, positionSeconds: number, durationSeconds: number): void {
    const token = this.auth.accessToken();
    if (!token) {
      return;
    }
    try {
      void fetch(`/api/episodes/${episodeId}/progress`, {
        method: 'PUT',
        keepalive: true,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ positionSeconds, durationSeconds }),
      }).catch(() => undefined);
    } catch {
      // navigateur sans fetch keepalive : la dernière position périodique reste
    }
  }
}
