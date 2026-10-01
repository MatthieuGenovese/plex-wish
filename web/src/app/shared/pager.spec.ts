import { pageWindow } from './pager';

describe('pageWindow', () => {
  it('montre première, dernière et voisines, avec « … » entre', () => {
    expect(pageWindow(0, 1)).toEqual([0]);
    expect(pageWindow(0, 5)).toEqual([0, 1, 2, null, 4]);
    expect(pageWindow(10, 22)).toEqual([0, null, 8, 9, 10, 11, 12, null, 21]);
  });
});
