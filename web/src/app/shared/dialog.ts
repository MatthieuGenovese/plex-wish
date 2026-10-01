/** Ouvre un <dialog> en modale (focus piégé, Échap pour fermer : gérés par le navigateur). */
export function openModal(dialog: HTMLDialogElement): void {
  if (typeof dialog.showModal === 'function') {
    dialog.showModal();
  } else {
    dialog.setAttribute('open', ''); // environnements sans <dialog> complet (tests)
  }
}

export function closeModal(dialog: HTMLDialogElement): void {
  if (typeof dialog.close === 'function') {
    dialog.close();
  } else {
    dialog.removeAttribute('open');
  }
}
