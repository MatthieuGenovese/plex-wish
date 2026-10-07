// Tests d'accessibilité automatiques (axe-core) pour les specs. Non inclus dans le build : seuls les specs l'importent.
import axe from 'axe-core';

/**
 * Violations axe d'un élément rendu (résumé lisible). Contraste désactivé : jsdom ne calcule pas les couleurs
 * (contrastes vérifiés à part, docs/DESIGN.md §5.1). « region » désactivé : un composant seul n'a pas de repères.
 */
export async function a11yViolations(root: Element): Promise<string[]> {
  const result = await axe.run(root, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    resultTypes: ['violations'],
  });
  return result.violations.map((v) => `${v.id} : ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(' | ')})`);
}
