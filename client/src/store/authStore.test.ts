import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from './authStore';

/**
 * FE-01 (absorbed FE-02): forceLogout in api/client.ts calls store.logout().catch(...). That is
 * safe only because logout() returns a promise that never rejects - pinned here, since the
 * declared type used to say "void" and broke the production type-check.
 */
describe('authStore.logout', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { id: 1, username: 'u', email: 'u@test', roles: ['ROLE_USER'] } as never,
      accessToken: 'access', refreshToken: 'refresh', isAuthenticated: true });
    localStorage.setItem('accessToken', 'access');
    localStorage.setItem('refreshToken', 'refresh');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('returns a promise that resolves even when the logout request fails, and clears the session', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('network down'));
    vi.stubGlobal('fetch', fetchMock);

    const result = useAuthStore.getState().logout();

    expect(result).toBeInstanceOf(Promise);
    await expect(result).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.accessToken).toBeNull();
    expect(state.refreshToken).toBeNull();
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('refreshToken')).toBeNull();
  });

  it('without a refresh token it skips the request and still clears the session', async () => {
    useAuthStore.setState({ refreshToken: null });
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().logout();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });
});
