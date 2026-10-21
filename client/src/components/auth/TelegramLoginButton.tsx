import { useEffect, useRef, useState } from 'react';
import { beginTelegramLogin, browser, isTelegramLoginEnabled } from '../../api/telegramAuth';
import { parseApiError } from '../../api/errors';

/**
 * AUTH-TELEGRAM-01: "Войти через Telegram" - one button for new and returning users (the Telegram identity
 * decides whether the account is created or found). Shown only when the backend has Telegram configured;
 * one click starts one round trip (repeated clicks are ignored while it starts).
 */
export function TelegramLoginButton({ label = 'Войти через Telegram' }: { label?: string }) {
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;
    isTelegramLoginEnabled().then(on => { if (alive) setEnabled(on); }).catch(() => undefined);
    return () => { alive = false; };
  }, []);

  if (!enabled) return null;

  const start = async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      browser.redirect(await beginTelegramLogin());
    } catch (e) {
      setError(parseApiError(e, 'Не удалось начать вход через Telegram. Попробуйте ещё раз.').message);
      inFlight.current = false;
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      <button type="button" onClick={start} disabled={busy}
        className="w-full min-h-11 flex items-center justify-center gap-2 py-3 px-4 text-sm font-medium rounded-xl text-white bg-[#229ED9] hover:bg-[#1c8cc2] focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-[#229ED9] disabled:opacity-50 transition-colors">
        <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5 fill-current">
          <path d="M9.04 15.3 8.9 19.2c.4 0 .58-.17.8-.38l1.92-1.84 3.98 2.92c.73.4 1.25.19 1.44-.67l2.61-12.24c.27-1.24-.45-1.73-1.16-1.46L3.2 9.48c-1.05.41-1.04 1-.18 1.27l3.93 1.23 9.13-5.76c.43-.28.82-.12.5.16z" />
        </svg>
        {busy ? 'Переходим в Telegram…' : label}
      </button>
      {error && <div role="alert" className="text-red-600 text-sm text-center bg-red-50 border border-red-200 rounded-lg p-3">{error}</div>}
    </div>
  );
}
