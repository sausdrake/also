import { useState, useRef, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { useNotifications } from '../hooks/useNotifications';
import type { Notification } from '../hooks/useNotifications';

type NotifCategory = 'ALL' | 'AUCTIONS' | 'DEPOSITS' | 'DIAGNOSTIC' | 'SYSTEM';

const CATEGORY_LABELS: { key: NotifCategory; label: string }[] = [
  { key: 'ALL',        label: 'Все'        },
  { key: 'AUCTIONS',   label: 'Аукционы'   },
  { key: 'DEPOSITS',   label: 'Депозиты'   },
  { key: 'DIAGNOSTIC', label: 'Диагностика'},
  { key: 'SYSTEM',     label: 'Система'    },
];

const TYPE_CATEGORY: Record<string, NotifCategory> = {
  NEW_BID_ON_VEHICLE: 'AUCTIONS', OUTBID: 'AUCTIONS', AUCTION_LEADER: 'AUCTIONS',
  AUCTION_WON: 'AUCTIONS', AUCTION_LOST: 'AUCTIONS', AUCTION_ENDING_SOON: 'AUCTIONS',
  AUCTION_ENDED: 'AUCTIONS', AUCTION_COMPLETED: 'AUCTIONS',
  DEPOSIT_ADDED: 'DEPOSITS', DEPOSIT_FROZEN: 'DEPOSITS',
  DEPOSIT_UNFROZEN: 'DEPOSITS', DEPOSIT_RELEASED: 'DEPOSITS',
  DIAG_ACCEPTED: 'DIAGNOSTIC', DIAG_REPORT_SUBMITTED: 'DIAGNOSTIC',
  DIAG_REPORT_APPROVED: 'DIAGNOSTIC', DIAG_REPORT_REJECTED: 'DIAGNOSTIC',
  VEHICLE_READY: 'DIAGNOSTIC',
};

const TYPE_ICON: Record<string, string> = {
  NEW_BID_ON_VEHICLE: '🚗', OUTBID: '📢', AUCTION_LEADER: '🥇',
  AUCTION_WON: '🏆', AUCTION_LOST: '😔', AUCTION_ENDING_SOON: '⏰',
  AUCTION_ENDED: '🏁', AUCTION_COMPLETED: '✅',
  DEPOSIT_ADDED: '💰', DEPOSIT_FROZEN: '🔒', DEPOSIT_UNFROZEN: '🔓', DEPOSIT_RELEASED: '💸',
  DIAG_ACCEPTED: '🔍', DIAG_REPORT_SUBMITTED: '📋',
  DIAG_REPORT_APPROVED: '✅', DIAG_REPORT_REJECTED: '❌', VEHICLE_READY: '🚀',
};

// success=green, warning=amber, error=red, info=blue, neutral=gray
const TYPE_COLOR: Record<string, string> = {
  AUCTION_WON: 'green', AUCTION_LEADER: 'green', DEPOSIT_ADDED: 'green',
  DEPOSIT_UNFROZEN: 'green', DEPOSIT_RELEASED: 'green',
  DIAG_REPORT_APPROVED: 'green', VEHICLE_READY: 'green', AUCTION_COMPLETED: 'green',
  OUTBID: 'amber', AUCTION_ENDING_SOON: 'amber', NEW_BID_ON_VEHICLE: 'amber',
  AUCTION_LOST: 'red', DIAG_REPORT_REJECTED: 'red',
  DEPOSIT_FROZEN: 'blue', DIAG_ACCEPTED: 'blue', DIAG_REPORT_SUBMITTED: 'blue',
};

const COLOR_CLASSES: Record<string, { bg: string; border: string; icon: string }> = {
  green: { bg: 'bg-green-50',  border: 'border-l-green-400',  icon: 'bg-green-100 text-green-700' },
  amber: { bg: 'bg-amber-50',  border: 'border-l-amber-400',  icon: 'bg-amber-100 text-amber-700' },
  red:   { bg: 'bg-red-50',    border: 'border-l-red-400',    icon: 'bg-red-100   text-red-700'   },
  blue:  { bg: 'bg-blue-50',   border: 'border-l-blue-400',   icon: 'bg-blue-100  text-blue-700'  },
  gray:  { bg: 'bg-gray-50',   border: 'border-l-gray-200',   icon: 'bg-gray-100  text-gray-600'  },
};

function relativeTime(iso: string): string {
  if (!iso) return '';
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 60_000)        return 'Только что';
  if (diff < 3_600_000)     return `${Math.floor(diff / 60_000)} мин назад`;
  if (diff < 86_400_000)    return `${Math.floor(diff / 3_600_000)} ч назад`;
  const d = new Date(iso);
  const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Вчера';
  return d.toLocaleDateString('ru', { day: '2-digit', month: '2-digit' });
}

function notifLink(n: Notification): string | null {
  if (n.referenceId) {
    if (n.referenceType === 'AUCTION')     return `/auction/${n.referenceId}`;
    if (n.referenceType === 'DIAGNOSTIC')  return `/diagnostic`;
  }
  const m = n.message?.match(/#(\d+)/);
  return m ? `/auction/${m[1]}` : null;
}

function NotifItem({
  n, onMarkRead, onClose,
}: { n: Notification; onMarkRead: (id: number) => void; onClose: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const colorKey = TYPE_COLOR[n.type] ?? 'gray';
  const cls = COLOR_CLASSES[colorKey];
  const icon = TYPE_ICON[n.type] ?? '🔔';
  const link = notifLink(n);
  const time = relativeTime(n.createdAt);

  const toggle = () => {
    if (!n.read) onMarkRead(n.id);
    setExpanded(v => !v);
  };

  return (
    <div className={`border-b last:border-0 border-l-4 ${cls.border} ${n.read ? 'opacity-60' : ''}`}>
      <button onClick={toggle} className={`w-full text-left px-3 py-2.5 hover:brightness-95 transition-all ${expanded ? cls.bg : ''}`}>
        <div className="flex items-start gap-2.5">
          <span className={`shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-sm ${cls.icon}`}>
            {icon}
          </span>
          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between gap-1">
              <p className={`text-sm leading-tight ${n.read ? 'font-normal' : 'font-semibold'} truncate`}>
                {n.title}
              </p>
              <div className="flex items-center gap-1 shrink-0 ml-1">
                {!n.read && <span className="w-2 h-2 rounded-full bg-blue-500" />}
                <span className="text-gray-300 text-xs">{expanded ? '▲' : '▼'}</span>
              </div>
            </div>
            <div className="flex items-center justify-between mt-0.5">
              {!expanded && <p className="text-xs text-gray-500 truncate flex-1">{n.message}</p>}
              <span className="text-xs text-gray-400 shrink-0 ml-2">{time}</span>
            </div>
          </div>
        </div>
      </button>

      {expanded && (
        <div className={`px-3 pb-3 ${cls.bg}`}>
          <p className="text-sm text-gray-700 leading-snug pl-9">{n.message}</p>
          {link && (
            <div className="pl-9 mt-2">
              <Link to={link} onClick={onClose}
                className={`text-xs font-medium hover:underline ${colorKey === 'red' ? 'text-red-600' : colorKey === 'green' ? 'text-green-600' : 'text-blue-600'}`}>
                Перейти →
              </Link>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function NotifDropdown({ notifications, unreadCount, markRead, markAllRead, onClose }: {
  notifications: Notification[];
  unreadCount: number;
  markRead: (id: number) => void;
  markAllRead: () => void;
  onClose: () => void;
}) {
  const [category, setCategory] = useState<NotifCategory>('ALL');

  const filtered = category === 'ALL'
    ? notifications
    : notifications.filter(n => (TYPE_CATEGORY[n.type] ?? 'SYSTEM') === category);

  const catUnread = (cat: NotifCategory) =>
    cat === 'ALL'
      ? unreadCount
      : notifications.filter(n => !n.read && (TYPE_CATEGORY[n.type] ?? 'SYSTEM') === cat).length;

  return (
    <div className="absolute right-0 top-full mt-1 w-96 bg-white rounded-xl shadow-xl border overflow-hidden z-50">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b bg-gray-50">
        <span className="font-semibold text-sm">Уведомления</span>
        {unreadCount > 0 && (
          <button onClick={markAllRead} className="text-xs text-blue-600 hover:underline">
            Прочитать все
          </button>
        )}
      </div>

      {/* Category tabs */}
      <div className="flex gap-0.5 px-2 py-1.5 border-b overflow-x-auto scrollbar-none bg-white">
        {CATEGORY_LABELS.map(({ key, label }) => {
          const cnt = catUnread(key);
          return (
            <button
              key={key}
              onClick={() => setCategory(key)}
              className={`shrink-0 px-2.5 py-1 rounded-full text-xs font-medium transition-colors whitespace-nowrap ${
                category === key
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-600 hover:bg-gray-100'
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

      {/* List */}
      <div className="max-h-[400px] overflow-y-auto">
        {filtered.length === 0 ? (
          <p className="text-center text-gray-400 text-sm py-8">Нет уведомлений</p>
        ) : (
          filtered.map(n => (
            <NotifItem key={n.id} n={n} onMarkRead={markRead} onClose={onClose} />
          ))
        )}
      </div>
    </div>
  );
}

export function Header() {
  const { user, isAuthenticated, logout } = useAuthStore();
  const navigate = useNavigate();
  const { notifications, unreadCount, markRead, markAllRead } = useNotifications();
  const [notifOpen, setNotifOpen] = useState(false);
  const [userOpen, setUserOpen] = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);
  const userRef = useRef<HTMLDivElement>(null);

  // Закрываем дропдауны при клике вне
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
      if (userRef.current && !userRef.current.contains(e.target as Node)) setUserOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const isAdmin = user?.roles?.some(r => r === 'ROLE_ADMIN' || r === 'ADMIN');
  const isDiagnostic = user?.roles?.some(r => r === 'ROLE_DIAGNOSTIC' || r === 'DIAGNOSTIC');

  return (
    <header className="bg-white border-b shadow-sm sticky top-0 z-40">
      <div className="max-w-7xl mx-auto px-4 h-14 flex items-center gap-4">
        {/* Логотип */}
        <Link to="/" className="text-xl font-bold text-blue-600 shrink-0">
          AutoAuction
        </Link>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Навигация */}
        {isAuthenticated && (
          <nav className="hidden md:flex items-center gap-1 text-sm">
            <Link to="/" className="px-3 py-1.5 rounded-lg text-gray-600 hover:text-gray-900 hover:bg-gray-100">Аукционы</Link>
            <Link to="/profile" className="px-3 py-1.5 rounded-lg text-gray-600 hover:text-gray-900 hover:bg-gray-100">Кабинет</Link>
            {isDiagnostic && <Link to="/diagnostic" className="px-3 py-1.5 rounded-lg text-gray-600 hover:text-gray-900 hover:bg-gray-100">Диагностика</Link>}
            {isAdmin && <Link to="/admin" className="px-3 py-1.5 rounded-lg text-gray-600 hover:text-gray-900 hover:bg-gray-100">Админ</Link>}
          </nav>
        )}

        {isAuthenticated ? (
          <div className="flex items-center gap-2">
            {/* Уведомления */}
            <div className="relative" ref={notifRef}>
              <button
                onClick={() => { setNotifOpen(v => !v); setUserOpen(false); }}
                className="relative p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100"
                aria-label="Уведомления"
              >
                🔔
                {unreadCount > 0 && (
                  <span className="absolute -top-0.5 -right-0.5 bg-red-500 text-white text-xs rounded-full w-4 h-4 flex items-center justify-center leading-none">
                    {unreadCount > 9 ? '9+' : unreadCount}
                  </span>
                )}
              </button>

              {notifOpen && (
                <NotifDropdown
                  notifications={notifications}
                  unreadCount={unreadCount}
                  markRead={markRead}
                  markAllRead={markAllRead}
                  onClose={() => setNotifOpen(false)}
                />
              )}
            </div>

            {/* Меню пользователя */}
            <div className="relative" ref={userRef}>
              <button
                onClick={() => { setUserOpen(v => !v); setNotifOpen(false); }}
                className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm text-gray-700 hover:bg-gray-100"
              >
                <span className="w-7 h-7 rounded-full bg-blue-600 text-white flex items-center justify-center text-xs font-bold">
                  {user?.username?.[0]?.toUpperCase() ?? '?'}
                </span>
                <span className="hidden sm:inline max-w-[100px] truncate">{user?.username}</span>
                <span className="text-gray-400 text-xs">▾</span>
              </button>

              {userOpen && (
                <div className="absolute right-0 top-full mt-1 w-44 bg-white rounded-xl shadow-lg border overflow-hidden z-50">
                  <Link to="/profile" onClick={() => setUserOpen(false)} className="block px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50">
                    Личный кабинет
                  </Link>
                  <Link to="/profile" onClick={() => setUserOpen(false)} className="block px-4 py-2.5 text-sm text-gray-700 hover:bg-gray-50">
                    Депозит
                  </Link>
                  <hr />
                  <button onClick={handleLogout} className="w-full text-left px-4 py-2.5 text-sm text-red-600 hover:bg-red-50">
                    Выйти
                  </button>
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <Link to="/login" className="px-4 py-1.5 text-sm text-gray-700 hover:text-gray-900 rounded-lg hover:bg-gray-100">
              Войти
            </Link>
            <Link to="/register" className="px-4 py-1.5 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700">
              Регистрация
            </Link>
          </div>
        )}
      </div>
    </header>
  );
}
