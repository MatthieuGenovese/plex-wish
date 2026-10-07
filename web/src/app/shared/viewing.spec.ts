import { isNew, longEpisode, minutesLabel, percent, remainingMinutes, shortEpisode } from './viewing';

describe('Calculs d’affichage', () => {
  const now = Date.parse('2026-10-07T12:00:00Z');

  it('nouveau pendant 14 jours', () => {
    expect(isNew('2026-10-07T10:00:00Z', now)).toBe(true);
    expect(isNew('2026-09-24T12:00:01Z', now)).toBe(true);
    expect(isNew('2026-09-23T11:59:59Z', now)).toBe(false);
    expect(isNew(null, now)).toBe(false);
    expect(isNew('pas une date', now)).toBe(false);
  });

  it('temps restant et part regardée', () => {
    expect(remainingMinutes(600, 1440)).toBe(14);
    expect(remainingMinutes(1430, 1440)).toBe(1);
    expect(remainingMinutes(0, 0)).toBeNull();
    expect(percent(720, 1440)).toBe(50);
    expect(percent(2000, 1440)).toBe(100);
    expect(percent(10, 0)).toBe(0);
  });

  it('libellés d’épisode et de durée', () => {
    expect(shortEpisode(1, 4)).toBe('S1 · É4');
    expect(shortEpisode(0, 2)).toBe('Spéciaux · É2');
    expect(longEpisode(2, 11)).toBe('Saison 2 · Épisode 11');
    expect(minutesLabel(1454)).toBe('24 min');
    expect(minutesLabel(null)).toBeNull();
  });
});
