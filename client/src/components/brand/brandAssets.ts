/**
 * FE-BRAND-01: the AlSo logo, from the PO's brand set in public/brand/ (used as is - never redrawn
 * or rasterised). One place knows the file names and intrinsic sizes, so every surface picks a
 * variant by meaning:
 * - full         light surfaces: header, admin and diagnostic sidebars, login
 * - full-on-dark dark surfaces (none in the UI today)
 * - simple       compact places (the mobile top bar of role areas)
 * - mini         square mark - the favicon (index.html)
 */
export const BRAND_ASSETS = {
  full: { src: '/brand/also-logo-full.svg', width: 480, height: 144 },
  'full-on-dark': { src: '/brand/also-logo-full-on-dark.svg', width: 480, height: 144 },
  simple: { src: '/brand/also-logo-simple.svg', width: 400, height: 112 },
  mini: { src: '/brand/also-logo-mini.svg', width: 64, height: 64 },
} as const;

export type BrandVariant = keyof typeof BRAND_ASSETS;
