import { describe, expect, it } from 'vitest';

/**
 * FE-BRAND-01 (name part): the product is AlSo. No user-visible "AutoAuction" may remain in the
 * client sources or index.html. (Code identifiers such as the Java package `autoauction` are not UI.)
 */
const sources = import.meta.glob(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const indexHtml = import.meta.glob('../index.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('FE-BRAND-01: product name', () => {
  it('no client source or index.html says "AutoAuction"', () => {
    const offenders = Object.entries({ ...sources, ...indexHtml }).filter(([, text]) => /AutoAuction/.test(text)).map(([p]) => p);
    expect(Object.keys(sources).length).toBeGreaterThan(30);
    expect(offenders).toEqual([]);
  });

  it('the page title is AlSo', () => {
    expect(Object.values(indexHtml)[0]).toMatch(/<title>AlSo<\/title>/);
  });
});
