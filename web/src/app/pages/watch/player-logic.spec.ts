import { episodeLine, formatTime, pickAudio, pickSubtitle, reportable, resumeFrom, spokenTime } from './player-logic';
import { WebAudioTrack, WebSubtitleTrack } from '../../core/playback-api';

const audio = (id: number, language: string | null): WebAudioTrack => ({ id, label: String(language), language, codec: 'aac', isDefault: id === 0 });
const sub = (id: number, language: string | null, extra: Partial<WebSubtitleTrack> = {}): WebSubtitleTrack => ({
  id, label: String(language), language, forced: false, isDefault: false, format: 'ass', url: '/a', vttUrl: '/v', ...extra,
});

describe('règles du lecteur web', () => {
  it('temps lisibles et parlés', () => {
    expect(formatTime(0)).toBe('0:00');
    expect(formatTime(754)).toBe('12:34');
    expect(formatTime(3723)).toBe('1:02:03');
    expect(formatTime(NaN)).toBe('0:00');
    expect(spokenTime(754)).toBe('12 minutes 34 secondes');
    expect(spokenTime(3600)).toBe('1 heure');
    expect(spokenTime(0)).toBe('0 seconde');
  });

  it('audio : langue mémorisée, sinon la première (japonais en tête côté serveur)', () => {
    const tracks = [audio(0, 'jpn'), audio(1, 'fre')];
    expect(pickAudio(tracks, null)).toBe(0);
    expect(pickAudio(tracks, 'fra')).toBe(1);
    expect(pickAudio(tracks, 'ger')).toBe(0);
  });

  it('sous-titres : désactivés si mémorisé, sinon français complet avant les forcés, puis par défaut', () => {
    const tracks = [sub(0, 'eng', { isDefault: true }), sub(1, 'fre', { forced: true }), sub(2, 'fre')];
    expect(pickSubtitle(tracks, null)).toBe(2);
    expect(pickSubtitle(tracks, 'off')).toBeNull();
    expect(pickSubtitle(tracks, 'eng')).toBe(0);
    expect(pickSubtitle([sub(0, null), sub(1, 'spa', { isDefault: true })], null)).toBe(1);
    expect(pickSubtitle([], null)).toBeNull();
  });

  it('reprise : pas pour un épisode fini, le tout début ou la toute fin', () => {
    const r = (positionSeconds: number, completed = false) => ({ resume: { positionSeconds, durationSeconds: 1440, completed }, durationSeconds: 1440 });
    expect(resumeFrom(r(600))).toBe(600);
    expect(resumeFrom(r(5))).toBe(0);
    expect(resumeFrom(r(1430))).toBe(0);
    expect(resumeFrom(r(600, true))).toBe(0);
    expect(resumeFrom({ resume: null, durationSeconds: 1440 })).toBe(0);
  });

  it('position envoyée seulement si elle a un sens', () => {
    expect(reportable(30, 1440)).toBe(true);
    expect(reportable(0, 1440)).toBe(false);
    expect(reportable(30, 0)).toBe(false);
    expect(reportable(2000, 1440)).toBe(false);
  });

  it('ligne d’épisode', () => {
    expect(episodeLine({ id: 1, animeId: 2, animeTitle: 'X', seasonNumber: 0, episodeNumber: 3, title: 'OAV', durationSeconds: null }))
      .toBe('Spéciaux · Épisode 3 · OAV');
  });
});
