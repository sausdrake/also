import axios from 'axios';

/**
 * AUTH-TELEGRAM-01: sign-in / linking with Telegram (OIDC Authorization Code + PKCE, done by the backend).
 * The browser only keeps a random binding secret for the round trip (sessionStorage, this tab) and hands
 * Telegram's code + state back to the backend - it never sees a client secret, an ID token or a Telegram id.
 */

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';
const PENDING_KEY = 'also.telegram.pending';

export type TelegramPurpose = 'LOGIN' | 'LINK';

export interface PendingTelegramAuth {
  binding: string;
  purpose: TelegramPurpose;
}

export interface TelegramLinkStatus {
  enabled: boolean;
  linked: boolean;
  displayName: string | null;
  phoneVerified: boolean;
  botCanMessage: boolean;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

/** Page navigation, kept in one place so tests can observe it. */
export const browser = {
  redirect: (url: string) => window.location.assign(url),
};

/** 32 random bytes, base64url - the browser half of the round trip (crypto.getRandomValues works on http too). */
export function newBinding(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function remember(pending: PendingTelegramAuth) {
  sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
}

/** Reads AND removes the pending round trip - a callback page can use it only once. */
export function takePendingTelegramAuth(): PendingTelegramAuth | null {
  const raw = sessionStorage.getItem(PENDING_KEY);
  sessionStorage.removeItem(PENDING_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as PendingTelegramAuth;
    return typeof parsed.binding === 'string' && (parsed.purpose === 'LOGIN' || parsed.purpose === 'LINK') ? parsed : null;
  } catch {
    return null;
  }
}

export async function isTelegramLoginEnabled(): Promise<boolean> {
  const { data } = await axios.get<{ enabled: boolean }>(`${BASE_URL}/api/auth/telegram/status`);
  return data.enabled === true;
}

/** Starts sign-in (new users get an account, returning users their own): returns Telegram's authorization URL. */
export async function beginTelegramLogin(includePhone = false): Promise<string> {
  const binding = newBinding();
  const { data } = await axios.post<{ authorizationUrl: string }>(`${BASE_URL}/api/auth/telegram/authorize`, { binding, includePhone });
  remember({ binding, purpose: 'LOGIN' });
  return data.authorizationUrl;
}

export async function completeTelegramLogin(code: string, state: string, binding: string): Promise<AuthTokens> {
  const { data } = await axios.post<AuthTokens>(`${BASE_URL}/api/auth/telegram/callback`, { code, state, binding });
  return data;
}
