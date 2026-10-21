import { BRAND_ASSETS, type BrandVariant } from './brandAssets';

/** FE-BRAND-01: renders one variant of the AlSo logo (see brandAssets.ts for the mapping). */
export function BrandLogo({ variant = 'full', className = 'h-8 w-auto' }: { variant?: BrandVariant; className?: string }) {
  const asset = BRAND_ASSETS[variant];
  // width/height reserve the aspect ratio before the SVG loads (no layout shift); CSS sets the size.
  return <img src={asset.src} width={asset.width} height={asset.height} alt="AlSo" className={className} />;
}
