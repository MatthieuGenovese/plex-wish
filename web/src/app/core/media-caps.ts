/**
 * Ce que ce navigateur sait décoder (docs/WEB-PLAYER.md §1), envoyé au serveur qui choisit la source. Mesuré, jamais
 * supposé : `MediaSource.isTypeSupported` (lecture HLS par hls.js) ou, à défaut, `canPlayType` (élément vidéo natif).
 * Le serveur ignore tout nom inconnu.
 */
const VIDEO: Record<string, string[]> = {
  h264: ['video/mp4; codecs="avc1.640028"', 'video/mp4; codecs="avc1.4d401f"'],
  hevc: ['video/mp4; codecs="hvc1.1.6.L120.90"'],
  hevc10: ['video/mp4; codecs="hvc1.2.4.L120.90"'],
  vp9: ['video/mp4; codecs="vp09.00.10.08"', 'video/webm; codecs="vp9"'],
  av1: ['video/mp4; codecs="av01.0.08M.08"'],
};

const AUDIO: Record<string, string[]> = {
  aac: ['audio/mp4; codecs="mp4a.40.2"'],
  mp3: ['audio/mp4; codecs="mp4a.40.34"', 'audio/mp4; codecs="mp4a.6B"', 'audio/mp4; codecs="mp3"'],
  opus: ['audio/mp4; codecs="opus"', 'audio/webm; codecs="opus"'],
  flac: ['audio/mp4; codecs="flac"'],
  ac3: ['audio/mp4; codecs="ac-3"'],
  eac3: ['audio/mp4; codecs="ec-3"'],
};

export type TypeTest = (type: string) => boolean;

/** Test par défaut : MediaSource si présent, sinon l'élément vidéo. */
export function browserTypeTest(): TypeTest {
  const ms = (globalThis as { MediaSource?: { isTypeSupported(t: string): boolean } }).MediaSource;
  if (ms && typeof ms.isTypeSupported === 'function') {
    return (t) => {
      try {
        return ms.isTypeSupported(t);
      } catch {
        return false;
      }
    };
  }
  const v = typeof document !== 'undefined' ? document.createElement('video') : null;
  return (t) => !!v && v.canPlayType(t) !== '';
}

export function detectCaps(test: TypeTest = browserTypeTest()): string[] {
  const caps: string[] = [];
  for (const [name, types] of Object.entries({ ...VIDEO, ...AUDIO })) {
    if (types.some(test)) {
      caps.push(name);
    }
  }
  return caps;
}
