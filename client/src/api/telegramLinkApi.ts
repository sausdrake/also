import api from './client';
import { newBinding, remember, type TelegramLinkStatus } from './telegramAuth';

/** AUTH-TELEGRAM-01: linking Telegram to the signed-in account (authenticated API - the account is the caller). */

/** Starts linking Telegram to the signed-in account. */
export async function beginTelegramLink(): Promise<string> {
  const binding = newBinding();
  const { data } = await api.post<{ authorizationUrl: string }>('/account/telegram/link/authorize', { binding });
  remember({ binding, purpose: 'LINK' });
  return data.authorizationUrl;
}

export async function completeTelegramLink(code: string, state: string, binding: string): Promise<TelegramLinkStatus> {
  const { data } = await api.post<TelegramLinkStatus>('/account/telegram/link/complete', { code, state, binding });
  return data;
}

export async function getTelegramLink(): Promise<TelegramLinkStatus> {
  const { data } = await api.get<TelegramLinkStatus>('/account/telegram');
  return data;
}
