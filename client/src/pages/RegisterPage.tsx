import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ACCEPTANCE_VERB, LEGAL_SLUGS, type LegalDocument } from '../domain/legal';
import axios from 'axios';
import { apiErrorMessage } from '../api/errors';
import { TelegramLoginButton } from '../components/auth/TelegramLoginButton';

export const RegisterPage = () => {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // LEGAL-DATA-01: the versions in force that must be accepted to register (none while nothing is in force)
  const [requiredDocs, setRequiredDocs] = useState<LegalDocument[]>([]);
  const [accepted, setAccepted] = useState<Record<number, boolean>>({});
  const navigate = useNavigate();

  useEffect(() => {
    Promise.resolve(axios.get<LegalDocument[]>(`${import.meta.env.VITE_API_URL || 'http://localhost:8081'}/api/v1/public/legal/documents`))
      .then(r => setRequiredDocs((Array.isArray(r?.data) ? r.data : [])
        .filter(d => d.acceptanceKind === 'ACCEPTANCE' || d.acceptanceKind === 'ACKNOWLEDGEMENT')))
      .catch(() => setRequiredDocs([]));
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    if (password !== confirmPassword) {
      setError('Пароли не совпадают');
      setLoading(false);
      return;
    }

    if (password.length < 6) {
      setError('Пароль должен содержать минимум 6 символов');
      setLoading(false);
      return;
    }

    if (requiredDocs.some(d => !accepted[d.id])) {
      setError('Отметьте документы платформы, чтобы зарегистрироваться');
      setLoading(false);
      return;
    }

    try {
      await axios.post(`${import.meta.env.VITE_API_URL || 'http://localhost:8081'}/api/auth/register`, {
        username,
        email,
        password,
        acceptedDocumentVersionIds: requiredDocs.map(d => d.id),
      });
      
      // После регистрации перенаправляем на логин
      // FE-UX-01: LoginPage shows `state.notice` (the key ChangePasswordPage uses); `message` was never read.
      navigate('/login', { state: { notice: 'Регистрация успешна! Войдите в систему.' } });
      
    } catch (err) {
      // BE-AUTH-01: a taken username/email is a 409 REGISTRATION_CONFLICT with one Russian message
      // that does not say which of the two is taken (anti-enumeration).
      setError(apiErrorMessage(err, 'Ошибка регистрации'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md w-full space-y-8">
        <div>
          <h2 className="mt-6 text-center text-3xl font-extrabold text-gray-900">
            Регистрация
          </h2>
        </div>

        {/* AUTH-TELEGRAM-01: no email, phone or password needed - Telegram creates the account */}
        <TelegramLoginButton label="Продолжить с Telegram" />
        
        <form className="mt-8 space-y-6" onSubmit={handleSubmit}>
          <div className="rounded-md shadow-sm -space-y-px">
            <div>
              <input
                type="text"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-t-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Имя пользователя"
              />
            </div>
            <div>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Email"
              />
            </div>
            <div>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Пароль (мин. 6 символов)"
              />
            </div>
            <div>
              <input
                type="password"
                required
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-b-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Подтвердите пароль"
              />
            </div>
          </div>

          {requiredDocs.length > 0 && (
            <div className="space-y-2">
              {requiredDocs.map(d => (
                <label key={d.id} className="flex items-start gap-2 text-sm text-gray-700">
                  <input type="checkbox" className="mt-0.5" checked={!!accepted[d.id]}
                    onChange={e => setAccepted(a => ({ ...a, [d.id]: e.target.checked }))} />
                  <span>
                    {ACCEPTANCE_VERB[d.acceptanceKind]}:{' '}
                    <Link to={`/legal/${LEGAL_SLUGS[d.documentType]}`} target="_blank" className="text-blue-600 underline">{d.title}</Link>
                    {' '}<span className="text-gray-400">(редакция {d.version})</span>
                  </span>
                </label>
              ))}
            </div>
          )}

          {error && (
            <div role="alert" className="text-red-500 text-sm text-center">{error}</div>
          )}

          <div>
            <button
              type="submit"
              disabled={loading}
              className="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              {loading ? 'Регистрация...' : 'Зарегистрироваться'}
            </button>
          </div>
          
          <div className="text-sm text-center">
            <a href="/login" className="text-blue-600 hover:text-blue-500">
              Уже есть аккаунт? Войти
            </a>
          </div>
        </form>
      </div>
    </div>
  );
};