import { useState, useRef, useEffect } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { NotificationBell } from './notifications/NotificationBell';
import { BrandLogo } from './brand/BrandLogo';
import { accountNav, primaryNav } from '../navigation/navigation';

const sectionLink = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-1.5 rounded-lg ${isActive ? 'text-blue-700 bg-blue-50 font-medium' : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'}`;

const menuLink = ({ isActive }: { isActive: boolean }) =>
  `flex items-center min-h-11 px-4 text-sm ${isActive ? 'text-blue-700 bg-blue-50 font-medium' : 'text-gray-700 hover:bg-gray-50'}`;

/**
 * G1: the site header. Sections (Аукционы, Кабинет, role areas) are the primary navigation - a row on
 * a wide screen; the account menu holds only the person (Профиль, Документы и данные, Выйти). Below
 * `md` the row does not fit, so the account menu starts with the same sections - one list,
 * `primaryNav`, for both.
 */
export function Header() {
  const { user, isAuthenticated, logout } = useAuthStore();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  // open on the page where it was opened: navigating elsewhere (a link, Back) closes it
  const [userOpenAt, setUserOpenAt] = useState<string | null>(null);
  const userOpen = userOpenAt === pathname;
  const setUserOpen = (v: boolean) => setUserOpenAt(v ? pathname : null);
  const userRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!userOpen) return;
    const onDown = (e: MouseEvent) => { if (userRef.current && !userRef.current.contains(e.target as Node)) setUserOpenAt(null); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setUserOpenAt(null); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [userOpen]);

  // G1 (NAV-BACK-LOGIN): the session is gone before /login opens (the login page sends a live session on),
  // and signing out does not leave a /login entry behind
  const handleLogout = async () => {
    setUserOpen(false);
    await logout();
    navigate('/login', { replace: true });
  };

  const sections = primaryNav(user?.roles);
  const account = accountNav(user?.roles);

  return (
    <header className="bg-white border-b shadow-sm sticky top-0 z-40">
      <div className="max-w-7xl mx-auto px-4 h-14 flex items-center gap-4">
        {/* Логотип */}
        <Link to="/" aria-label="AlSo — на главную" className="shrink-0 flex items-center">
          <BrandLogo variant="full" className="h-9 w-auto" />
        </Link>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Разделы (wide screen) */}
        {isAuthenticated && (
          <nav aria-label="Разделы" className="hidden md:flex items-center gap-1 text-sm">
            {sections.map(s => <NavLink key={s.to} to={s.to} end={s.end} className={sectionLink}>{s.label}</NavLink>)}
          </nav>
        )}

        {isAuthenticated ? (
          <div className="flex items-center gap-2">
            <NotificationBell onOpen={() => setUserOpen(false)} />

            {/* Меню пользователя */}
            <div className="relative" ref={userRef}>
              <button
                onClick={() => setUserOpen(!userOpen)}
                aria-label={`Меню пользователя ${user?.username ?? ''}`.trim()}
                aria-expanded={userOpen}
                aria-controls="account-menu"
                className="flex items-center gap-2 px-3 min-h-10 rounded-lg text-sm text-gray-700 hover:bg-gray-100"
              >
                <span className="w-7 h-7 rounded-full bg-blue-600 text-white flex items-center justify-center text-xs font-bold">
                  {user?.username?.[0]?.toUpperCase() ?? '?'}
                </span>
                <span className="hidden sm:inline max-w-[100px] truncate">{user?.username}</span>
                <span className="text-gray-400 text-xs" aria-hidden="true">▾</span>
              </button>

              {userOpen && (
                <div id="account-menu" className="absolute right-0 top-full mt-1 w-56 max-w-[calc(100vw-2rem)] bg-white rounded-xl shadow-lg border overflow-hidden z-50">
                  <nav aria-label="Разделы (меню)" data-testid="account-menu-sections" className="md:hidden border-b py-1">
                    {sections.map(s => <NavLink key={s.to} to={s.to} end={s.end} className={menuLink}>{s.label}</NavLink>)}
                  </nav>
                  <nav aria-label="Аккаунт" className="py-1">
                    <p className="px-4 pt-2 pb-1 text-xs text-gray-400 truncate">{user?.username}</p>
                    {account.map(a => <NavLink key={a.to} to={a.to} end className={menuLink}>{a.label}</NavLink>)}
                  </nav>
                  <hr />
                  <button onClick={handleLogout} className="w-full text-left flex items-center min-h-11 px-4 text-sm text-red-600 hover:bg-red-50">
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
