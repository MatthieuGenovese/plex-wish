// Thème posé AVANT le premier rendu (pas de flash clair → sombre). Fichier séparé : la CSP interdit les scripts
// en ligne. Préférence enregistrée sur cet appareil par le menu Compte (ThemeService) : system | dark | light.
(function () {
  var pref = 'dark';
  try {
    pref = localStorage.getItem('theme') || 'dark';
  } catch (e) {}
  var dark = pref === 'system' ? !(window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches) : pref !== 'light';
  document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
})();
