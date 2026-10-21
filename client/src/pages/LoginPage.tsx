import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import axios from 'axios';
import { parseApiError } from '../api/errors';
import { homePathFor, sessionUserFromToken } from '../auth/session';
import { BrandLogo } from '../components/brand/BrandLogo';
import { TelegramLoginButton } from '../components/auth/TelegramLoginButton';
import { useAuthStore } from '../store/authStore';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

export const LoginPage = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuthStore();
  const navigate = useNavigate();
  const notice: string | undefined = (useLocation().state as { notice?: string } | null)?.notice;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const { data } = await axios.post(`${BASE_URL}/api/auth/login`, { username, password });

      const accessToken: string = data.accessToken;
      const refreshToken: string = data.refreshToken || '';

      if (!accessToken) throw new Error('Токен не получен');

      const user = sessionUserFromToken(accessToken, username);
      login(user, accessToken, refreshToken);
      // Редирект по роли
      navigate(homePathFor(user.roles));
    } catch (err) {
      // FE-UX-01: every authentication failure is one masked 401 "Invalid username or password" on
      // the backend (it does not reveal whether the account exists or is disabled) - say it in Russian.
      const error = parseApiError(err, 'Не удалось войти. Повторите попытку.');
      setError(error.kind === 'unauthorized' ? 'Неверное имя пользователя или пароль.' : error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md w-full space-y-8">
        <div>
          <h1 className="flex justify-center"><BrandLogo variant="full" className="h-14 w-auto" /></h1>
          <h2 className="mt-2 text-center text-xl text-gray-600">Вход в систему</h2>
        </div>

        {notice && <div role="status" className="text-green-700 text-sm text-center bg-green-50 border border-green-200 rounded-lg p-3">{notice}</div>}

        {/* AUTH-TELEGRAM-01: Telegram-first - the same button signs in and creates an account */}
        <TelegramLoginButton />

        <form className="mt-8 space-y-6" onSubmit={handleSubmit}>
          <div className="rounded-xl shadow-sm space-y-3">
            <input
              type="text" required value={username}
              onChange={e => setUsername(e.target.value)}
              placeholder="Имя пользователя"
              className="appearance-none block w-full px-4 py-3 border border-gray-300 rounded-xl placeholder-gray-400 text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent sm:text-sm"
            />
            <input
              type="password" required value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="Пароль"
              className="appearance-none block w-full px-4 py-3 border border-gray-300 rounded-xl placeholder-gray-400 text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent sm:text-sm"
            />
          </div>

          {error && <div className="text-red-600 text-sm text-center bg-red-50 border border-red-200 rounded-lg p-3">{error}</div>}

          <button
            type="submit" disabled={loading}
            className="w-full flex justify-center py-3 px-4 border border-transparent text-sm font-medium rounded-xl text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Входим...' : 'Войти'}
          </button>

          <p className="text-center text-sm text-gray-600">
            Нет аккаунта?{' '}
            <Link to="/register" className="text-blue-600 hover:text-blue-700 font-medium">Зарегистрироваться</Link>
          </p>
        </form>
      </div>
    </div>
  );
};
