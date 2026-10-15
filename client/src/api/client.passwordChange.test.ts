import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * AUTH-01: signed in with a temporary password, every API call answers 403
 * PASSWORD_CHANGE_REQUIRED - the response interceptor sends the user to the change-password page.
 * Same harness as client.test.ts: the real interceptor is captured from a fresh module instance.
 */

const hoisted = vi.hoisted(() => {
  const mockApiInstance = Object.assign(vi.fn(), {
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
  });
  return { mockApiInstance, axiosPost: vi.fn(), mockGetState: vi.fn(), mockSetState: vi.fn() };
});

vi.mock('axios', () => ({
  default: { create: vi.fn(() => hoisted.mockApiInstance), post: hoisted.axiosPost },
}));

vi.mock('../store/authStore', () => ({
  useAuthStore: { getState: hoisted.mockGetState, setState: hoisted.mockSetState },
}));

type ResponseErrorHandler = (error: unknown) => Promise<unknown>;

async function loadHandler(): Promise<ResponseErrorHandler> {
  hoisted.mockApiInstance.interceptors.response.use.mockClear();
  await import('./client');
  const [, onRejected] = hoisted.mockApiInstance.interceptors.response.use.mock.calls[0];
  return onRejected as ResponseErrorHandler;
}

function error403(data: unknown) {
  return { response: { status: 403, data }, config: { url: '/x', headers: {} } };
}

beforeEach(() => {
  vi.resetModules();
  hoisted.axiosPost.mockReset();
  hoisted.mockGetState.mockReturnValue({ isAuthenticated: true, user: { id: 1 }, logout: vi.fn().mockResolvedValue(undefined) });
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('client.ts - PASSWORD_CHANGE_REQUIRED', () => {
  it('redirects to /change-password?required=1 and rejects the call, without a refresh', async () => {
    const handler = await loadHandler();
    const popstate = vi.fn();
    window.addEventListener('popstate', popstate);

    await expect(handler(error403({ error: 'PASSWORD_CHANGE_REQUIRED' }))).rejects.toBeTruthy();

    expect(window.location.pathname).toBe('/change-password');
    expect(window.location.search).toBe('?required=1');
    expect(popstate).toHaveBeenCalledTimes(1);
    expect(hoisted.axiosPost).not.toHaveBeenCalled();
    window.removeEventListener('popstate', popstate);
  });

  it('an ordinary 403 does not redirect', async () => {
    const handler = await loadHandler();
    await expect(handler(error403({ error: 'Доступ запрещён' }))).rejects.toBeTruthy();
    expect(window.location.pathname).toBe('/');
  });

  it('already on the change-password page: no second redirect', async () => {
    const handler = await loadHandler();
    window.history.replaceState(null, '', '/change-password');
    const popstate = vi.fn();
    window.addEventListener('popstate', popstate);

    await expect(handler(error403({ error: 'PASSWORD_CHANGE_REQUIRED' }))).rejects.toBeTruthy();

    expect(popstate).not.toHaveBeenCalled();
    window.removeEventListener('popstate', popstate);
  });
});
