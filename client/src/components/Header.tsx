import { useState, useRef, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { useNotifications } from '../hooks/useNotifications';
import { NotificationPanel } from './notifications/NotificationPanel';
import { BrandLogo } from './brand/BrandLogo';

export function Header() {
  const { user, isAuthenticated, logout } = useAuthStore();
  const navigate = useNavigate();
  const { notifications, unreadCount, status, loadError, actionError, markRead, markAllRead, reload } = useNotifications();
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
        <Link to="/" aria-label="AlSo — на главную" className="shrink-0 flex items-center">
          <BrandLogo variant="full" className="h-9 w-auto" />
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
                aria-label={unreadCount > 0 ? `Уведомления, непрочитанных: ${unreadCount}` : 'Уведомления'}
                aria-expanded={notifOpen}
              >
                🔔
                {unreadCount > 0 && (
                  <span className="absolute -top-0.5 -right-0.5 bg-red-500 text-white text-xs rounded-full w-4 h-4 flex items-center justify-center leading-none">
                    {unreadCount > 9 ? '9+' : unreadCount}
                  </span>
                )}
              </button>

              {notifOpen && (
                <NotificationPanel
                  notifications={notifications}
                  unreadCount={unreadCount}
                  status={status}
                  loadError={loadError}
                  actionError={actionError}
                  onMarkRead={markRead}
                  onMarkAllRead={markAllRead}
                  onRetry={reload}
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
