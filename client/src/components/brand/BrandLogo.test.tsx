import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { BrandLogo } from './BrandLogo';
import { BRAND_ASSETS, type BrandVariant } from './brandAssets';
import { Header } from '../Header';
import { SidebarLayout } from '../layout/SidebarLayout';
import { AdminDashboard } from '../../pages/AdminDashboard';
import { DiagnosticDashboard } from '../../pages/DiagnosticDashboard';
import { LoginPage } from '../../pages/LoginPage';

/**
 * FE-BRAND-01: the PO's mapping - favicon = mini, header / admin + diagnostic sidebars / login = full,
 * compact (role-area mobile top bar) = simple, dark surfaces = full-on-dark. The SVGs are the PO's
 * files, used as they are.
 */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: [] })), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const svgs = import.meta.glob('../../../public/brand/*.svg', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const indexHtml = Object.values(import.meta.glob('../../../index.html', { query: '?raw', import: 'default', eager: true }) as Record<string, string>)[0];
const fileOf = (src: string) => svgs[`../../../public${src}`];

describe('FE-BRAND-01: assets', () => {
  it.each(Object.entries(BRAND_ASSETS))('%s points at an existing SVG whose intrinsic size matches', (_variant, asset) => {
    const svg = fileOf(asset.src);
    expect(svg, asset.src).toBeDefined();
    expect(svg).toMatch(new RegExp(`width="${asset.width}"`));
    expect(svg).toMatch(new RegExp(`height="${asset.height}"`));
  });

  it('no brand SVG carries active content or external references', () => {
    expect(Object.keys(svgs).length).toBeGreaterThanOrEqual(4);
    for (const [path, svg] of Object.entries(svgs)) {
      expect(svg, path).not.toMatch(/<script|on[a-z]+\s*=|javascript:|<foreignObject|href="https?:|url\(https?:/i);
    }
  });

  it('the dark variant really is for dark surfaces (white wordmark) and the light one is not', () => {
    expect(fileOf(BRAND_ASSETS['full-on-dark'].src)).toMatch(/fill="#FFFFFF"/);
    expect(fileOf(BRAND_ASSETS.full.src)).not.toMatch(/fill="#FFFFFF"/);
  });

  it('the favicon is the mini mark', () => {
    expect(indexHtml).toContain(`<link rel="icon" type="image/svg+xml" href="${BRAND_ASSETS.mini.src}" />`);
    expect(indexHtml).not.toContain('/vite.svg');
  });

  it.each(Object.keys(BRAND_ASSETS) as BrandVariant[])('<BrandLogo variant="%s"> renders that file with alt "AlSo" and reserved size', variant => {
    render(<BrandLogo variant={variant} />);
    const img = screen.getByAltText('AlSo');
    expect(img).toHaveAttribute('src', BRAND_ASSETS[variant].src);
    expect(img).toHaveAttribute('width', String(BRAND_ASSETS[variant].width));
    expect(img).toHaveAttribute('height', String(BRAND_ASSETS[variant].height));
  });
});

describe('FE-BRAND-01: every surface uses its mapped variant', () => {
  const srcs = () => screen.getAllByAltText('AlSo').map(i => i.getAttribute('src'));
  const routed = (path: string, pattern: string, el: React.ReactNode) =>
    render(<RouterProvider router={createMemoryRouter([{ path: pattern, element: el }], { initialEntries: [path] })} />);

  it('header: full', () => {
    routed('/', '/', <Header />);
    expect(srcs()).toEqual([BRAND_ASSETS.full.src]);
    expect(screen.getByRole('link', { name: 'AlSo — на главную' })).toHaveAttribute('href', '/');
  });

  it('login: full', () => {
    routed('/login', '/login', <LoginPage />);
    expect(srcs()).toEqual([BRAND_ASSETS.full.src]);
  });

  it('admin area: full in the sidebar, simple in the compact top bar', () => {
    routed('/admin/settings', '/admin/*', <AdminDashboard />);
    expect(srcs().sort()).toEqual([BRAND_ASSETS.full.src, BRAND_ASSETS.simple.src].sort());
  });

  it('diagnostic area: full in the sidebar, simple in the compact top bar', () => {
    routed('/diagnostic/dashboard', '/diagnostic/*', <DiagnosticDashboard />);
    expect(srcs().sort()).toEqual([BRAND_ASSETS.full.src, BRAND_ASSETS.simple.src].sort());
  });

  it('a role-area layout alone: the top bar uses the compact mark', () => {
    render(<SidebarLayout title="Тест" sidebar={() => null}><p>x</p></SidebarLayout>);
    expect(srcs()).toEqual([BRAND_ASSETS.simple.src]);
  });
});
