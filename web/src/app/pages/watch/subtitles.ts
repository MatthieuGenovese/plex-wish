import { WebSubtitleTrack } from '../../core/playback-api';

/** Sous-titres affichés par-dessus la vidéo (WebVTT natif ; ASS : voir ass-subtitles.ts). */
export interface SubtitleRenderer {
  destroy(): void;
}

/**
 * Piste WebVTT native (`<track>`), stylée par `::cue` (styles.scss). {@code offset} : décalage à appliquer aux
 * temps des sous-titres (copie HLS qui commence à 0 alors que les sous-titres gardent l'horloge du fichier).
 */
export class VttSubtitles implements SubtitleRenderer {
  private readonly el: HTMLTrackElement;

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
    if (Math.abs(offset) > 0.01) {
      this.el.addEventListener('load', () => shift(this.el.track, offset), { once: true });
    }
    video.appendChild(this.el);
    // Une seule piste affichée à la fois.
    queueMicrotask(() => {
      for (const t of Array.from(video.textTracks ?? [])) {
        t.mode = t === this.el.track ? 'showing' : 'disabled';
      }
    });
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
