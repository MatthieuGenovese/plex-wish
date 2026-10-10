import { defineConfig } from '@playwright/test';

/**
 * Essais de bout en bout du lecteur web (phase 10) contre une vraie pile (nginx avec sa CSP, serveur, ffmpeg) :
 * scripts/test/e2e-web.sh la démarre, génère les fichiers de test et lance ces essais.
 * Navigateur : Google Chrome (H.264 / AAC, que le Chromium de Playwright ne décode pas) : CHROME_PATH, sinon le
 * Chrome installé (canal « chrome »).
 */
const domain = process.env['E2E_DOMAIN'] ?? 'anime.e2e.test';
// Le navigateur croit parler au port 443 (comme derrière le routeur du NAS : même origine que l'adresse publique),
// la connexion va en fait au port public de l'essai.
const port = process.env['E2E_PORT'] ?? '18444';

export default defineConfig({
  testDir: 'e2e',
  timeout: 120_000,
  expect: { timeout: 30_000 },
  retries: 0,
  workers: 1,
  reporter: [['list']],
  outputDir: 'e2e-results',
  use: {
    baseURL: `https://${domain}`,
    ignoreHTTPSErrors: true, // certificat interne de Caddy pour l'essai
    viewport: { width: 1280, height: 720 },
    locale: 'fr-FR',
    launchOptions: {
      executablePath: process.env['CHROME_PATH'] || undefined,
      // Le nom de l'essai pointe vers cette machine (sans proxy) ; lecture automatique avec le son permise.
      args: [`--host-resolver-rules=MAP ${domain} 127.0.0.1:${port}`, '--autoplay-policy=no-user-gesture-required',
        '--no-proxy-server'],
    },
    channel: process.env['CHROME_PATH'] ? undefined : 'chrome',
  },
});
