import { detectCaps } from './media-caps';

describe('capacités du navigateur', () => {
  it('liste ce que le test accepte, avec les noms attendus par le serveur', () => {
    const chrome = (t: string) => /avc1|mp4a\.40\.2|opus|flac|vp09|av01/.test(t);
    expect(detectCaps(chrome)).toEqual(['h264', 'vp9', 'av1', 'aac', 'opus', 'flac']);
    const withHevc = (t: string) => chrome(t) || t.includes('hvc1');
    expect(detectCaps(withHevc)).toContain('hevc10');
    expect(detectCaps(() => false)).toEqual([]);
  });
});
