import { SubtitleRenderer } from './subtitles';

/**
 * Sous-titres ASS/SSA rendus fidèlement par JASSUB (libass en WebAssembly, docs/WEB-PLAYER.md §3) : styles,
 * positions, polices jointes du fichier. Chargé seulement quand un épisode en a. Tout vient du site (aucun CDN) :
 * worker et WebAssembly sous /jassub/ (scripts/jassub-assets.mjs), polices et sous-titres par URL signées.
 * Un seul fil de calcul (pas d'en-têtes COOP/COEP, §9 S4). En cas d'échec, {@code onError} : le lecteur passe au
 * WebVTT.
 */
export class AssSubtitles implements SubtitleRenderer {
  private instance: { destroy(): Promise<void> } | null = null;
  private destroyed = false;
  private ready = false;

  constructor(video: HTMLVideoElement, url: string, fonts: string[], offset: number, onError: (reason: unknown) => void) {
    // Ce qu'il faut à JASSUB (navigateurs récents) : sinon, WebVTT tout de suite.
    const g = globalThis as { OffscreenCanvas?: unknown; Worker?: unknown; WebAssembly?: unknown; HTMLCanvasElement?: { prototype: object } };
    if (!g.OffscreenCanvas || !g.Worker || !g.WebAssembly || !g.HTMLCanvasElement || !('transferControlToOffscreen' in g.HTMLCanvasElement.prototype)) {
      queueMicrotask(() => onError(new Error('JASSUB non pris en charge')));
      return;
    }
    // Garde-fou : prêt en quelques secondes d'habitude ; sinon (WebAssembly bloqué…), WebVTT.
    const watchdog = setTimeout(() => {
      if (!this.destroyed && !this.ready) onError(new Error('JASSUB : délai dépassé'));
    }, 20_000);
    import('jassub')
      .then(({ default: JASSUB }) => {
        if (this.destroyed) return;
        const jassub = new JASSUB({
          video,
          subUrl: url,
          fonts,
          workerUrl: '/jassub/worker.js',
          wasmUrl: '/jassub/jassub-worker.wasm',
          modernWasmUrl: '/jassub/jassub-worker-modern.wasm',
          availableFonts: { 'liberation sans': '/jassub/default.woff2' },
          defaultFont: 'liberation sans',
          // Pas de recherche de polices sur Internet ni sur l'ordinateur (permission) : polices jointes, sinon défaut.
          queryFonts: false,
          // Copie HLS : la vidéo commence à 0, les sous-titres gardent l'horloge du fichier (offset = décalage des
          // sous-titres ; JASSUB ajoute timeOffset au temps de la vidéo).
          timeOffset: -offset,
        });
        this.instance = jassub;
        jassub.ready.then(
          () => {
            this.ready = true;
            clearTimeout(watchdog);
          },
          (e: unknown) => {
            clearTimeout(watchdog);
            if (!this.destroyed) onError(e);
          },
        );
      })
      .catch((e: unknown) => {
        clearTimeout(watchdog);
        if (!this.destroyed) onError(e);
      });
  }

  destroy(): void {
    this.destroyed = true;
    void this.instance?.destroy().catch(() => undefined);
    this.instance = null;
  }
}
