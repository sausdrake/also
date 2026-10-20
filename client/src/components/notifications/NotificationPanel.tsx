import { useState } from 'react';
import { Link } from 'react-router-dom';
import { parseDate } from '../../utils/formatDate';
import {
  notificationCategory, notificationLink, type NotificationCategory, type NotificationType, type UserNotification,
} from '../../domain/notification';
import type { NotificationsStatus } from '../../hooks/useNotifications';

type CategoryFilter = 'ALL' | NotificationCategory;

const CATEGORY_LABELS: { key: CategoryFilter; label: string }[] = [
  { key: 'ALL',        label: 'Все'         },
  { key: 'AUCTIONS',   label: 'Аукционы'    },
  { key: 'DEPOSITS',   label: 'Депозиты'    },
  { key: 'DIAGNOSTIC', label: 'Диагностика' },
  { key: 'SYSTEM',     label: 'Система'     },
];

const TYPE_ICON: Partial<Record<NotificationType, string>> = {
  NEW_BID_ON_VEHICLE: '🚗', OUTBID: '📢', AUCTION_LEADER: '🥇', AUCTION_WON: '🏆', AUCTION_LOST: '😔',
  AUCTION_ENDING_SOON: '⏰', AUCTION_ENDED: '🏁', AUCTION_COMPLETED: '✅', AUCTION_CANCELLED: '🚫',
  DEPOSIT_FROZEN: '🔒', DEPOSIT_UNFROZEN: '🔓', DEPOSIT_RELEASED: '💸',
  DIAG_ACCEPTED: '🔍', DIAG_REPORT_SUBMITTED: '📋', DIAG_REPORT_APPROVED: '✅', DIAG_REPORT_REJECTED: '❌',
  DIAG_REDIAGNOSTIC_ORDERED: '🔁',
};

type Tone = 'green' | 'amber' | 'red' | 'blue' | 'gray';

// success=green, warning=amber, error=red, info=blue, neutral=gray
const TYPE_TONE: Partial<Record<NotificationType, Tone>> = {
  AUCTION_WON: 'green', AUCTION_LEADER: 'green', DEPOSIT_UNFROZEN: 'green', DEPOSIT_RELEASED: 'green',
  DIAG_REPORT_APPROVED: 'green', AUCTION_COMPLETED: 'green',
  OUTBID: 'amber', AUCTION_ENDING_SOON: 'amber', NEW_BID_ON_VEHICLE: 'amber', DIAG_REDIAGNOSTIC_ORDERED: 'amber',
  AUCTION_LOST: 'red', DIAG_REPORT_REJECTED: 'red', AUCTION_CANCELLED: 'red',
  DEPOSIT_FROZEN: 'blue', DIAG_ACCEPTED: 'blue', DIAG_REPORT_SUBMITTED: 'blue',
};

const TONE_CLASSES: Record<Tone, { bg: string; border: string; icon: string; link: string }> = {
  green: { bg: 'bg-green-50', border: 'border-l-green-400', icon: 'bg-green-100 text-green-700', link: 'text-green-600' },
  amber: { bg: 'bg-amber-50', border: 'border-l-amber-400', icon: 'bg-amber-100 text-amber-700', link: 'text-blue-600' },
  red:   { bg: 'bg-red-50',   border: 'border-l-red-400',   icon: 'bg-red-100 text-red-700',     link: 'text-red-600' },
  blue:  { bg: 'bg-blue-50',  border: 'border-l-blue-400',  icon: 'bg-blue-100 text-blue-700',   link: 'text-blue-600' },
  gray:  { bg: 'bg-gray-50',  border: 'border-l-gray-200',  icon: 'bg-gray-100 text-gray-600',   link: 'text-blue-600' },
};

function relativeTime(iso: string | null): string {
  const parsed = iso ? parseDate(iso) : null;
  if (!parsed || Number.isNaN(parsed.getTime())) return '';
  const diff = Date.now() - parsed.getTime();
  if (diff < 60_000)     return 'Только что';
  if (diff < 3_600_000)  return `${Math.floor(diff / 60_000)} мин назад`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} ч назад`;
  const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
  if (parsed.toDateString() === yesterday.toDateString()) return 'Вчера';
  return parsed.toLocaleDateString('ru', { day: '2-digit', month: '2-digit' });
}

function NotificationItem({ n, onMarkRead, onClose }: {
  n: UserNotification; onMarkRead: (id: number) => void; onClose: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const type = n.type as NotificationType;
  const tone = TONE_CLASSES[TYPE_TONE[type] ?? 'gray'];
  const link = notificationLink(n);

  const toggle = () => {
    if (!n.read) onMarkRead(n.id);
    setExpanded(v => !v);
  };

  return (
    <div className={`border-b last:border-0 border-l-4 ${tone.border} ${n.read ? 'opacity-60' : ''}`}>
      <button onClick={toggle} aria-expanded={expanded}
        className={`w-full text-left px-3 py-2.5 hover:brightness-95 transition-all ${expanded ? tone.bg : ''}`}>
        <div className="flex items-start gap-2.5">
          <span className={`shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-sm ${tone.icon}`}>
            {TYPE_ICON[type] ?? '🔔'}
          </span>
          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between gap-1">
              <p className={`text-sm leading-tight ${n.read ? 'font-normal' : 'font-semibold'} truncate`}>
                {n.title || 'Уведомление'}
              </p>
              <div className="flex items-center gap-1 shrink-0 ml-1">
                {!n.read && <span className="w-2 h-2 rounded-full bg-blue-500" aria-label="Не прочитано" />}
                <span className="text-gray-300 text-xs">{expanded ? '▲' : '▼'}</span>
              </div>
            </div>
            <div className="flex items-center justify-between mt-0.5">
              {!expanded && <p className="text-xs text-gray-500 truncate flex-1">{n.message}</p>}
              <span className="text-xs text-gray-400 shrink-0 ml-2">{relativeTime(n.createdAt)}</span>
            </div>
          </div>
        </div>
      </button>

      {expanded && (
        <div className={`px-3 pb-3 ${tone.bg}`}>
          <p className="text-sm text-gray-700 leading-snug pl-9 break-words">{n.message}</p>
          {link && (
            <div className="pl-9 mt-2">
              <Link to={link} onClick={onClose} className={`text-xs font-medium hover:underline ${tone.link}`}>
                Перейти →
              </Link>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export interface NotificationPanelProps {
  notifications: UserNotification[];
  unreadCount: number;
  status: NotificationsStatus;
  loadError: string;
  actionError: string;
  onMarkRead: (id: number) => void;
  onMarkAllRead: () => void;
  onRetry: () => void;
  onClose: () => void;
}

/**
 * FE-NOTIF-01: the inbox dropdown. On a phone it spans the viewport under the 56px header
 * (it used to be a fixed 384px box anchored to the bell and was cut off at 375px); from `sm` up it is
 * the 384px dropdown. A failed load is an alert with "Повторить" - "Нет уведомлений" only after a
 * successful empty answer.
 */
export function NotificationPanel({
  notifications, unreadCount, status, loadError, actionError, onMarkRead, onMarkAllRead, onRetry, onClose,
}: NotificationPanelProps) {
  const [category, setCategory] = useState<CategoryFilter>('ALL');

  const filtered = category === 'ALL' ? notifications : notifications.filter(n => notificationCategory(n) === category);
  const catUnread = (cat: CategoryFilter) =>
    cat === 'ALL' ? unreadCount : notifications.filter(n => !n.read && notificationCategory(n) === cat).length;

  return (
    <div role="dialog" aria-label="Уведомления"
      className="fixed inset-x-2 top-14 sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-1 sm:w-96 bg-white rounded-xl shadow-xl border overflow-hidden z-50">
      <div className="flex items-center justify-between px-4 py-2.5 border-b bg-gray-50">
        <span className="font-semibold text-sm">Уведомления</span>
        {status === 'ready' && unreadCount > 0 && (
          <button onClick={onMarkAllRead} className="text-xs text-blue-600 hover:underline">
            Прочитать все
          </button>
        )}
      </div>

      <div className="flex gap-0.5 px-2 py-1.5 border-b overflow-x-auto scrollbar-none bg-white">
        {CATEGORY_LABELS.map(({ key, label }) => {
          const cnt = catUnread(key);
          return (
            <button
              key={key}
              onClick={() => setCategory(key)}
              className={`shrink-0 px-2.5 py-1 rounded-full text-xs font-medium transition-colors whitespace-nowrap ${
                category === key ? 'bg-blue-600 text-white' : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {label}
              {cnt > 0 && (
                <span className={`ml-1 text-xs rounded-full px-1 ${category === key ? 'bg-blue-500 text-white' : 'bg-red-100 text-red-600'}`}>
                  {cnt}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {status === 'error' && (
        <div role="alert" className="m-2 p-3 rounded-lg bg-red-50 border border-red-200 text-sm text-red-700 space-y-2">
          <p>{loadError}</p>
          <button onClick={onRetry} className="px-3 py-1.5 bg-white border border-red-300 rounded-lg text-xs font-medium hover:bg-red-100">
            Повторить
          </button>
        </div>
      )}
      {actionError && (
        <div role="alert" className="mx-2 mt-2 p-2 rounded-lg bg-amber-50 border border-amber-200 text-xs text-amber-800">
          {actionError}
        </div>
      )}

      <div className="max-h-[60vh] sm:max-h-[400px] overflow-y-auto">
        {status === 'loading' ? (
          <p className="text-center text-gray-400 text-sm py-8">Загрузка...</p>
        ) : filtered.length === 0 ? (
          status === 'ready' && <p className="text-center text-gray-400 text-sm py-8">Нет уведомлений</p>
        ) : (
          filtered.map(n => <NotificationItem key={n.id} n={n} onMarkRead={onMarkRead} onClose={onClose} />)
        )}
      </div>
    </div>
  );
}
