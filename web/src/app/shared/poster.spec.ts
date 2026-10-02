import { TestBed } from '@angular/core/testing';
import { Poster } from './poster';

describe('Poster', () => {
  async function render(url: string | null) {
    const fixture = TestBed.createComponent(Poster);
    fixture.componentRef.setInput('title', 'Sousou no Frieren');
    fixture.componentRef.setInput('url', url);
    await fixture.whenStable();
    return fixture;
  }

  it('affiche l’image quand il y a une affiche', async () => {
    const f = await render('https://s4.anilist.co/x.jpg');
    const img = (f.nativeElement as HTMLElement).querySelector('img')!;
    expect(img.getAttribute('src')).toBe('https://s4.anilist.co/x.jpg');
    expect(img.getAttribute('alt')).toBe('');
  });

  it('visuel de remplacement sans affiche, ou si l’image ne charge pas', async () => {
    const none = await render(null);
    expect((none.nativeElement as HTMLElement).querySelector('[data-testid=poster-placeholder]')?.textContent).toBe('SN');

    const broken = await render('https://s4.anilist.co/mort.jpg');
    (broken.nativeElement as HTMLElement).querySelector('img')!.dispatchEvent(new Event('error'));
    await broken.whenStable();
    expect((broken.nativeElement as HTMLElement).querySelector('img')).toBeNull();
    expect((broken.nativeElement as HTMLElement).querySelector('[data-testid=poster-placeholder]')).not.toBeNull();
  });
});
