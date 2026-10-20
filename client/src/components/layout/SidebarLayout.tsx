import { useEffect, useState, type ReactNode } from 'react';

export interface SidebarLayoutProps {
  /** Shown in the mobile top bar and used to name the drawer. */
  title: string;
  /**
   * Renders the navigation. `onNavigate` closes the mobile drawer - pass it to every link and to
   * logout. On a wide screen it is a no-op.
   */
  sidebar: (onNavigate: () => void) => ReactNode;
  children: ReactNode;
}

/**
 * FE-MOBILE-01: role area layout (diagnostician, later admin). From `lg` (1024px) the sidebar is a
 * static column as before. Below it the 240px sidebar used to eat most of a phone screen and clip
 * the actions; now it is a drawer opened from a top bar ("Открыть меню"), closed by navigating, by
 * Escape or by tapping the backdrop, and the content gets the full width.
 */
export function SidebarLayout({ title, sidebar, children }: SidebarLayoutProps) {
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      <div className="hidden lg:flex shrink-0">{sidebar(() => {})}</div>

      {open && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label={`Меню: ${title}`}>
          <div className="absolute inset-0 bg-black/40" onClick={close} data-testid="drawer-backdrop" />
          <div className="relative h-full w-64 max-w-[85vw] bg-white shadow-xl overflow-y-auto">
            <button type="button" onClick={close} aria-label="Закрыть меню"
              className="absolute top-3 right-3 z-10 w-8 h-8 rounded-lg text-gray-500 hover:bg-gray-100 flex items-center justify-center">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
            {sidebar(close)}
          </div>
        </div>
      )}

      <div className="flex-1 flex flex-col min-w-0">
        <div className="lg:hidden h-14 shrink-0 flex items-center gap-2 px-3 bg-white border-b border-gray-100">
          <button type="button" onClick={() => setOpen(true)} aria-label="Открыть меню" aria-expanded={open}
            className="w-10 h-10 rounded-lg text-gray-700 hover:bg-gray-100 flex items-center justify-center">
            <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <span className="font-semibold text-sm text-gray-900 truncate">{title}</span>
        </div>
        <main className="flex-1 overflow-y-auto min-w-0">{children}</main>
      </div>
    </div>
  );
}
