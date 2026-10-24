import { NavLink, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/authStore';
import { roleAreaAccountNav } from '../../navigation/navigation';

/**
 * G1: the bottom of a role area's sidebar (/diagnostic, /admin) - the way back to the public site,
 * the person's own profile (password, Telegram) and sign-out. The site header is hidden in these
 * areas, so without it they were dead ends.
 */
export function RoleAreaFooter({ onNavigate }: { onNavigate: () => void }) {
  const navigate = useNavigate();
  const roles = useAuthStore(s => s.user?.roles);
  const logoutFn = useAuthStore(s => s.logout);

  const handleLogout = async () => {
    onNavigate();
    await logoutFn();
    navigate('/login', { replace: true });   // G1: signing out does not leave a /login entry behind
  };

  return (
    <div className="border-t border-gray-100 p-3 shrink-0 space-y-0.5">
      <nav aria-label="Аккаунт" className="space-y-0.5">
        {roleAreaAccountNav(roles).map(item => (
          <NavLink key={item.to} to={item.to} end={item.end} onClick={onNavigate}
            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-gray-600 hover:bg-gray-50 hover:text-gray-900 transition-colors">
            {item.label}
          </NavLink>
        ))}
      </nav>
      <button onClick={handleLogout}
        className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-red-600 hover:bg-red-50 transition-colors">
        <svg className="w-5 h-5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
        </svg>
        Выйти
      </button>
    </div>
  );
}
