import { Page, expect, test } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Lecteur web de bout en bout (phase 10), sur la pile démarrée par scripts/test/e2e-web.sh, dans Google Chrome :
 * fichiers générés (MKV H.264 + deux pistes AAC + ASS + police jointe ; MP4 avec sous-titres mov_text ; AVI Xvid).
 * Aucun écart à la CSP n'est toléré.
 */
const USER = process.env['E2E_USER'] ?? 'chef';
const PASSWORD = process.env['E2E_PASSWORD'] ?? 'mot-de-passe-du-chef';
const SHOTS = join(__dirname, 'screenshots');
mkdirSync(SHOTS, { recursive: true });

const csp: string[] = [];

async function login(page: Page): Promise<void> {
  // Écarts à la CSP vus par la page elle-même (rapport de sécurité du navigateur).
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      (window as unknown as { __csp: string[] }).__csp ??= [];
      (window as unknown as { __csp: string[] }).__csp.push(`${e.violatedDirective} ${e.blockedURI}`);
    });
  });
  page.on('console', (m) => {
    if (/Content Security Policy|Refused to/i.test(m.text())) csp.push(m.text());
  });
  await page.goto('/login');
  await page.locator('#login').fill(USER);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/$/);
}

async function openEpisode(page: Page, anime: string, episode: number): Promise<void> {
  await page.goto('/anime');
  await page.getByRole('link', { name: new RegExp(anime) }).first().click();
  await page.getByRole('link', { name: new RegExp(`Lire l’épisode ${episode}\\b`) }).click();
  await expect(page).toHaveURL(/\/regarder\/\d+$/);
}

async function waitPlaying(page: Page, minTime = 1.5): Promise<void> {
  await page.waitForFunction((t) => {
    const v = document.querySelector('video');
    return !!v && !v.paused && v.currentTime > t;
  }, minTime, { timeout: 100_000 });
}

async function noCspViolation(page: Page): Promise<void> {
  const inPage = await page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? []);
  expect([...csp, ...inPage]).toEqual([]);
}

async function axe(page: Page): Promise<string[]> {
  // Exécuté par l'outil de test (hors CSP de la page), comme les tests d'accessibilité des composants.
  const source = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
  return page.evaluate(async (src) => {
    // eslint-disable-next-line no-eval
    (0, eval)(src);
    const w = window as unknown as { axe: { run(n: Element, o: object): Promise<{ violations: { id: string; impact: string; nodes: unknown[] }[] }> } };
    const r = await w.axe.run(document.querySelector('.player')!, { resultTypes: ['violations'] });
    return r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => v.id);
  }, source);
}

test.beforeEach(() => {
  csp.length = 0;
});

test('MKV : copie HLS, deux pistes audio, ASS rendu par JASSUB, clavier, progression et reprise', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (r) => requests.push(new URL(r.url()).pathname));
  await login(page);
  await openEpisode(page, 'Frieren', 1);
  await waitPlaying(page);
  // Copie HLS lue par hls.js (MediaSource) : source « blob: ».
  expect(await page.evaluate(() => document.querySelector('video')!.src)).toMatch(/^blob:/);
  expect(requests.some((p) => p.endsWith('/master.m3u8'))).toBe(true);
  // Sous-titres ASS : JASSUB chargé depuis le site (worker + WebAssembly), son calque est sur la vidéo.
  await expect(page.locator('canvas.JASSUB')).toHaveCount(1);
  expect(requests).toContain('/jassub/worker.js');
  expect(requests.some((p) => p.startsWith('/jassub/') && p.endsWith('.wasm'))).toBe(true);
  expect(requests.some((p) => /\/sub_0\.ass$/.test(p))).toBe(true);
  expect(requests.some((p) => /\/font_0\.ttf$/.test(p))).toBe(true);
  await page.mouse.move(640, 360);
  await page.screenshot({ path: join(SHOTS, 'lecteur-mkv-hls.png') });

  // Panneau « Audio et sous-titres » : deux langues, sous-titres ; passage à la VF.
  await page.getByRole('button', { name: 'Audio et sous-titres' }).click();
  const panel = page.getByRole('dialog', { name: 'Audio et sous-titres' });
  await expect(panel.getByRole('radio')).toHaveCount(4); // Japonais, Français (VF), Désactivés, Français
  await page.screenshot({ path: join(SHOTS, 'lecteur-pistes.png') });
  await panel.getByRole('radio', { name: 'Français (VF)' }).check();
  await expect.poll(() => requests.some((p) => p.endsWith('/s_2.m3u8'))).toBe(true);
  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
  expect(await axe(page)).toEqual([]);

  // Clavier : K pause, flèche droite +10 s, M muet.
  await page.locator('body').click({ position: { x: 5, y: 5 } }).catch(() => undefined);
  await page.keyboard.press('k');
  await expect.poll(() => page.evaluate(() => document.querySelector('video')!.paused)).toBe(true);
  const before = await page.evaluate(() => document.querySelector('video')!.currentTime);
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => page.evaluate(() => document.querySelector('video')!.currentTime)).toBeGreaterThan(before + 5);
  await page.keyboard.press('m');
  expect(await page.evaluate(() => document.querySelector('video')!.muted)).toBe(true);

  // Position enregistrée (pause), reprise proposée au retour.
  const saved = page.waitForResponse((r) => /\/api\/episodes\/\d+\/progress$/.test(r.url()) && r.request().method() === 'PUT');
  await page.keyboard.press('k');
  await page.keyboard.press('k');
  expect((await saved).status()).toBe(200);
  await page.reload();
  await expect(page.getByTestId('notice')).toContainText('Reprise à');
  await noCspViolation(page);
});

test('MP4 : lu tel quel (élément vidéo natif), sous-titres WebVTT', async ({ page }) => {
  await login(page);
  await openEpisode(page, 'Frieren', 2);
  await waitPlaying(page);
  const src = await page.evaluate(() => document.querySelector('video')!.src);
  expect(src).toContain('/api/stream/');
  expect(src).not.toContain('/web/');
  await expect(page.locator('video track')).toHaveAttribute('src', /sub_\d+\.vtt/);
  await page.mouse.move(640, 360);
  // Les deux répliques sont là, remontées au-dessus de la barre de commandes visible.
  await expect.poll(() => page.evaluate(() => {
    const cues = Array.from(document.querySelector('video')!.textTracks[0]?.cues ?? []) as VTTCue[];
    return cues.map((c) => c.line);
  })).toEqual([-4, -4]);
  await page.screenshot({ path: join(SHOTS, 'lecteur-mp4-direct.png') });
  await noCspViolation(page);
});

test('AVI Xvid : non pris en charge par le navigateur, raison claire', async ({ page }) => {
  await login(page);
  await openEpisode(page, 'Air Gear', 1);
  const card = page.getByTestId('unsupported');
  await expect(card).toContainText('MPEG-4 ASP');
  await expect(card.getByRole('link', { name: 'Retour à la fiche' })).toBeVisible();
  await page.screenshot({ path: join(SHOTS, 'lecteur-non-pris-en-charge.png') });
  expect(await axe(page)).toEqual([]);
  await noCspViolation(page);
});

test('fin d’épisode : « Épisode suivant » avec compte à rebours, puis lecture du suivant', async ({ page }) => {
  await login(page);
  await openEpisode(page, 'Frieren', 1);
  await waitPlaying(page, 0.5);
  await page.evaluate(() => {
    const v = document.querySelector('video')!;
    v.currentTime = Math.max(0, v.duration - 1.5);
  });
  const next = page.getByTestId('next');
  await expect(next).toContainText('Épisode suivant dans');
  await page.screenshot({ path: join(SHOTS, 'lecteur-episode-suivant.png') });
  await next.getByRole('button', { name: 'Lire maintenant' }).click();
  await waitPlaying(page, 0.5);
  await expect(page.locator('.player-subtitle')).toContainText('Épisode 2');
  await noCspViolation(page);
});
