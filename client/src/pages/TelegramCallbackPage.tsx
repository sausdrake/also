import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { parseApiError } from '../api/errors';
import { beginTelegramLogin, browser, completeTelegramLogin, takePendingTelegramAuth } from '../api/telegramAuth';
import { completeTelegramLink } from '../api/telegramLinkApi';
import { homePathFor, sessionUserFromToken } from '../auth/session';
import { BrandLogo } from '../components/brand/BrandLogo';
import { useAuthStore } from '../store/authStore';

/**
 * AUTH-TELEGRAM-01: Telegram redirects here (the registered redirect_uri) with ?code&state, or ?error when the
 * user declined. The page hands code + state + this tab's binding secret to the backend exactly once and signs
 * in (or finishes linking). A declined, expired or foreign round trip ends with a clear message and a retry.
 */
export function TelegramCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuthStore();
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;
    const pending = takePendingTelegramAuth();
    const code = params.get('code');
    const state = params.get('state');
    const denied = params.get('error');

    const finish = async () => {
      if (denied) {
        setError(denied === 'access_denied' ? 'Вы отменили вход через Telegram.' : 'Telegram не подтвердил вход.');
        return;
      }
      if (!pending || !code || !state) {
        setError('Ссылка входа через Telegram недействительна или устарела. Начните вход заново.');
        return;
      }
      try {
        if (pending.purpose === 'LINK') {
          await completeTelegramLink(code, state, pending.binding);
          navigate('/profile/settings', { replace: true });   // the profile tab: its Telegram card now shows "Привязан"
          return;
        }
        const tokens = await completeTelegramLogin(code, state, pending.binding);
        const user = sessionUserFromToken(tokens.accessToken);
        login(user, tokens.accessToken, tokens.refreshToken);
        navigate(homePathFor(user.roles), { replace: true });
      } catch (e) {
        setError(parseApiError(e, 'Не удалось войти через Telegram. Попробуйте ещё раз.').message);
      }
    };
    void finish();
  }, [params, login, navigate]);

  const retry = async () => {
    if (retrying) return;
    setRetrying(true);
    try {
      browser.redirect(await beginTelegramLogin());
    } catch (e) {
      setError(parseApiError(e, 'Не удалось начать вход через Telegram.').message);
      setRetrying(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4">
      <div className="max-w-md w-full space-y-6 text-center">
        <h1 className="flex justify-center"><BrandLogo variant="full" className="h-14 w-auto" /></h1>
        {error ? (
          <div className="space-y-4" data-testid="telegram-callback-error">
            <div role="alert" className="text-red-600 text-sm bg-red-50 border border-red-200 rounded-lg p-3">{error}</div>
            <button type="button" onClick={retry} disabled={retrying}
              className="w-full min-h-11 py-3 px-4 text-sm font-medium rounded-xl text-white bg-[#229ED9] hover:bg-[#1c8cc2] disabled:opacity-50">
              {retrying ? 'Переходим в Telegram…' : 'Попробовать снова'}
            </button>
            <Link to="/login" className="block text-sm text-blue-600 hover:text-blue-700">Войти по логину и паролю</Link>
          </div>
        ) : (
          <p role="status" className="text-gray-600">Входим через Telegram…</p>
        )}
      </div>
    </div>
  );
}
