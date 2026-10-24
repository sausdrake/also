/**
 * AUTH-TELEGRAM-01: one way to turn the backend's AlSo token pair into the signed-in session, for password
 * login and Telegram sign-in alike. The user comes from the access token the BACKEND issued (roles, id) -
 * the frontend never creates or alters a token.
 */

export interface SessionUser {
  id: number;
  username: string;
  email: string | null;
  roles: string[];
}

function decodePayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const binary = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(binary, c => c.charCodeAt(0))));
  } catch {
    return null;
  }
}

export function sessionUserFromToken(accessToken: string, fallbackUsername = ''): SessionUser {
  const payload = decodePayload(accessToken) ?? {};
  const roles = (payload.roles ?? payload.authorities ?? []) as string[];
  return {
    id: Number(payload.userId ?? 0),
    username: (payload.username as string | undefined) ?? fallbackUsername,
    email: (payload.email as string | null | undefined) ?? null,
    roles,
  };
}

/**
 * The session is over when the access token is unreadable, has no expiry or has expired. G1: one rule for the
 * route guard and the login page, so the guard's "sign in" and the login page's "already signed in" never
 * both hold (no redirect loop).
 */
export function isAccessTokenExpired(accessToken: string): boolean {
  const exp = decodePayload(accessToken)?.exp;
  return typeof exp !== 'number' || Date.now() / 1000 > exp;
}

/** Where a freshly signed-in user lands, by role. */
export function homePathFor(roles: string[]): string {
  const normalized = roles.map(r => r.replace('ROLE_', '').toUpperCase());
  if (normalized.includes('ADMIN')) return '/admin';
  if (normalized.includes('DIAGNOSTIC')) return '/diagnostic';
  return '/';
}
