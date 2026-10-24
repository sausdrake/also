import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useNotifications } from '../../hooks/useNotifications';
import { NotificationPanel } from './NotificationPanel';

/**
 * G1: the bell and its inbox, one component for the site header and the role areas' top bar (the
 * admin and diagnostician areas hide the site header, so they had no inbox of their own). Closes on
 * a click outside, on Escape and when the route changes.
 */
export function NotificationBell({ onOpen }: { onOpen?: () => void }) {
  const { notifications, unreadCount, status, loadError, actionError, markRead, markAllRead, reload } = useNotifications();
  const { pathname } = useLocation();
  // open on the page where it was opened: navigating elsewhere closes it
  const [openAt, setOpenAt] = useState<string | null>(null);
  const open = openAt === pathname;
  const setOpen = (v: boolean) => setOpenAt(v ? pathname : null);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpenAt(null); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpenAt(null); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => { if (!open) onOpen?.(); setOpen(!open); }}
        className="relative w-10 h-10 flex items-center justify-center rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100"
        aria-label={unreadCount > 0 ? `Уведомления, непрочитанных: ${unreadCount}` : 'Уведомления'}
        aria-expanded={open}
      >
        🔔
        {unreadCount > 0 && (
          <span className="absolute top-0.5 right-0.5 bg-red-500 text-white text-xs rounded-full w-4 h-4 flex items-center justify-center leading-none">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <NotificationPanel
          notifications={notifications}
          unreadCount={unreadCount}
          status={status}
          loadError={loadError}
          actionError={actionError}
          onMarkRead={markRead}
          onMarkAllRead={markAllRead}
          onRetry={reload}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  );
}
