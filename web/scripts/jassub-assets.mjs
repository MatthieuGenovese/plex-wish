// Fichiers de JASSUB (rendu des sous-titres ASS, docs/WEB-PLAYER.md §3) servis par le site, sans CDN :
// - le worker, regroupé en un seul module ES (il importe abslink et lfa-ponyfill, que le navigateur ne sait pas
//   résoudre seul) ;
// - les deux versions du WebAssembly (avec et sans SIMD) et la police par défaut.
// Sortie : generated/jassub/ (ignoré par git), copié dans le site sous /jassub/ (angular.json, « assets »).
// Lancé avant chaque build (npm run build / npm start).
import { build } from 'esbuild';
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(web, 'node_modules', 'jassub', 'dist');
const out = join(web, 'generated', 'jassub');
mkdirSync(out, { recursive: true });

await build({
  entryPoints: [join(dist, 'worker', 'worker.js')],
  outfile: join(out, 'worker.js'),
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2022',
  minify: true,
  legalComments: 'eof',
  logLevel: 'warning',
});
for (const f of ['wasm/jassub-worker.wasm', 'wasm/jassub-worker-modern.wasm', 'default.woff2']) {
  copyFileSync(join(dist, f), join(out, f.split('/').pop()));
}
console.log('jassub : worker, WebAssembly et police par défaut prêts dans generated/jassub/');
