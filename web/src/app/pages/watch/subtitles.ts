import { WebSubtitleTrack } from '../../core/playback-api';

/** Sous-titres affichés par-dessus la vidéo (WebVTT natif ; ASS : voir ass-subtitles.ts). */
export interface SubtitleRenderer {
  /** Commandes visibles : sous-titres remontés au-dessus d'elles (si le rendu le permet). */
  raise?(on: boolean): void;
  destroy(): void;
}

/**
 * Piste WebVTT native (`<track>`), stylée par `::cue` (styles.scss). {@code offset} : décalage à appliquer aux
 * temps des sous-titres (copie HLS qui commence à 0 alors que les sous-titres gardent l'horloge du fichier).
 */
export class VttSubtitles implements SubtitleRenderer {
  private readonly el: HTMLTrackElement;
  private raised = false;

  constructor(
    private readonly video: HTMLVideoElement,
    track: Pick<WebSubtitleTrack, 'label' | 'language'>,
    url: string,
    offset: number,
  ) {
    this.el = document.createElement('track');
    this.el.kind = 'subtitles';
    this.el.label = track.label;
    if (track.language) {
      this.el.srclang = track.language.length === 2 ? track.language : track.language === 'jpn' ? 'ja' : 'fr';
    }
    this.el.src = url;
    this.el.default = true;
    this.el.addEventListener('load', () => {
      if (Math.abs(offset) > 0.01) shift(this.el.track, offset);
      this.place();
    }, { once: true });
    video.appendChild(this.el);
    // Une seule piste affichée à la fois.
    queueMicrotask(() => {
      for (const t of Array.from(video.textTracks ?? [])) {
        t.mode = t === this.el.track ? 'showing' : 'disabled';
      }
    });
  }

  raise(on: boolean): void {
    this.raised = on;
    this.place();
  }

  /** Lignes comptées depuis le bas : -4 laisse la place de la barre de commandes, sinon placement normal. */
  private place(): void {
    for (const cue of Array.from(this.el.track?.cues ?? [])) {
      if ('line' in cue) (cue as VTTCue).line = this.raised ? -4 : 'auto';
    }
  }

  destroy(): void {
    if (this.el.track) {
      this.el.track.mode = 'disabled';
    }
    this.el.remove();
  }
}

function shift(track: TextTrack, offset: number): void {
  for (const cue of Array.from(track.cues ?? [])) {
    cue.startTime = Math.max(0, cue.startTime + offset);
    cue.endTime = Math.max(0, cue.endTime + offset);
  }
}
