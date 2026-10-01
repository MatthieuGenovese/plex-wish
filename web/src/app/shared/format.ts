const dateTime = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'medium' });

export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) : '—';
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—';
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const s = ms / 1000;
  return s < 60 ? `${s.toFixed(1).replace('.', ',')} s` : `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`;
}

export function formatNumber(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : n.toLocaleString('fr-FR');
}
