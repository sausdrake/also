import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';

/**
 * AUTH-01: the user replaces their password. Also the only page available while a temporary
 * password set by an administrator is in use (the API answers 403 PASSWORD_CHANGE_REQUIRED
 * everywhere else). After the change every session ends and the user signs in again.
 */
export function ChangePasswordPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const required = new URLSearchParams(location.search).get('required') === '1';
  const { logout } = useAuthStore();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (next !== confirm) {
      setError('Новый пароль и подтверждение не совпадают');
      return;
    }
    setSaving(true);
    try {
      await api.post('/account/password', { currentPassword: current, newPassword: next });
      await logout().catch(() => {});
      navigate('/login', { replace: true, state: { notice: 'Пароль изменён. Войдите с новым паролем.' } });
    } catch (err: any) {
      setError(err.response?.data?.message || 'Не удалось сменить пароль');
    } finally {
      setSaving(false);
    }
  };

  const input = 'block w-full px-4 py-3 border border-gray-300 rounded-xl text-gray-900 focus:outline-none focus:ring-2 focus:ring-blue-500 sm:text-sm';

  return (
    <div className="max-w-md mx-auto p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-2">Смена пароля</h1>
      {required && (
        <p role="status" className="text-sm bg-amber-50 border border-amber-200 text-amber-800 rounded-lg p-3 mb-4">
          Вы вошли с временным паролем, который выдала поддержка. Задайте свой пароль, чтобы продолжить.
        </p>
      )}
      <form onSubmit={submit} className="space-y-3">
        <label className="block text-sm text-gray-600">
          {required ? 'Временный пароль' : 'Текущий пароль'}
          <input type="password" required autoComplete="current-password" value={current}
            onChange={e => setCurrent(e.target.value)} className={input} />
        </label>
        <label className="block text-sm text-gray-600">
          Новый пароль (не короче 6 символов)
          <input type="password" required minLength={6} autoComplete="new-password" value={next}
            onChange={e => setNext(e.target.value)} className={input} />
        </label>
        <label className="block text-sm text-gray-600">
          Повторите новый пароль
          <input type="password" required minLength={6} autoComplete="new-password" value={confirm}
            onChange={e => setConfirm(e.target.value)} className={input} />
        </label>
        {error && <div role="alert" className="text-red-600 text-sm bg-red-50 border border-red-200 rounded-lg p-3">{error}</div>}
        <button type="submit" disabled={saving}
          className="w-full py-3 px-4 text-sm font-medium rounded-xl text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-50">
          {saving ? 'Сохраняем...' : 'Сменить пароль'}
        </button>
      </form>
    </div>
  );
}
