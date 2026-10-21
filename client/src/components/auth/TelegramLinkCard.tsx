import { useEffect, useRef, useState } from 'react';
import { browser, type TelegramLinkStatus } from '../../api/telegramAuth';
import { beginTelegramLink, getTelegramLink } from '../../api/telegramLinkApi';
import { parseApiError } from '../../api/errors';

/**
 * AUTH-TELEGRAM-01: links Telegram to the signed-in account (always the current account - no id is sent).
 * Shows only the owner's own state: linked or not, the Telegram display name, whether a verified phone came
 * with it. Never the Telegram id or the phone number.
 */
export function TelegramLinkCard() {
  const [status, setStatus] = useState<TelegramLinkStatus | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;
    getTelegramLink().then(s => { if (alive) setStatus(s); }).catch(() => undefined);
    return () => { alive = false; };
  }, []);

  if (!status || (!status.enabled && !status.linked)) return null;

  const link = async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      browser.redirect(await beginTelegramLink());
    } catch (e) {
      setError(parseApiError(e, 'Не удалось начать привязку Telegram.').message);
      inFlight.current = false;
      setBusy(false);
    }
  };

  return (
    <div data-testid="telegram-link">
      <p className="text-sm text-gray-500">Telegram</p>
      {status.linked ? (
        <p className="font-medium">
          Привязан{status.displayName ? ` (${status.displayName})` : ''}
          {status.phoneVerified && <span className="ml-2 text-xs text-green-700">телефон подтверждён</span>}
        </p>
      ) : (
        <button type="button" onClick={link} disabled={busy}
          className="mt-1 min-h-11 px-4 rounded-lg text-sm font-medium text-white bg-[#229ED9] hover:bg-[#1c8cc2] disabled:opacity-50">
          {busy ? 'Переходим в Telegram…' : 'Привязать Telegram'}
        </button>
      )}
      {error && <p role="alert" className="text-xs text-red-600 mt-1">{error}</p>}
    </div>
  );
}
