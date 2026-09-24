import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Phase 86: regression coverage for the token-refresh response interceptor in ./client.ts.
 * The interceptor's actual error handler is captured from the real module (never
 * reimplemented) and invoked directly with synthetic AxiosError-shaped objects, so these
 * tests exercise the production code path, not a parallel description of it.
 *
 * client.ts holds module-level state (isRefreshing / failedQueue), so every test gets a
 * fully fresh module instance via vi.resetModules() + a dynamic re-import - no test may
 * inherit refresh state from another.
 */

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function make401Error(url: string, authHeader = 'Bearer old-access-token') {
  return {
    response: { status: 401 },
    config: { url, headers: { Authorization: authHeader } },
  };
}

function makeStatusError(status: number, url = '/some/endpoint') {
  return {
    response: { status },
    config: { url, headers: { Authorization: 'Bearer old-access-token' } },
  };
}

const hoisted = vi.hoisted(() => {
  const mockApiInstance = Object.assign(vi.fn(), {
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() },
    },
  });
  return {
    mockApiInstance,
    axiosPost: vi.fn(),
    mockGetState: vi.fn(),
    mockSetState: vi.fn(),
  };
});

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => hoisted.mockApiInstance),
    post: hoisted.axiosPost,
  },
}));

vi.mock('../store/authStore', () => ({
  useAuthStore: {
    getState: hoisted.mockGetState,
    setState: hoisted.mockSetState,
  },
}));

type ResponseErrorHandler = (error: unknown) => Promise<unknown>;

/** Fresh-imports client.ts and returns the captured response-interceptor error handler. */
async function loadClientAndCaptureHandler(): Promise<ResponseErrorHandler> {
  hoisted.mockApiInstance.mockReset();
  hoisted.mockApiInstance.interceptors.request.use.mockClear();
  hoisted.mockApiInstance.interceptors.response.use.mockClear();

  await import('./client');

  const [, onRejected] = hoisted.mockApiInstance.interceptors.response.use.mock.calls[0];
  return onRejected as ResponseErrorHandler;
}

function makeLogoutMock() {
  return vi.fn().mockResolvedValue(undefined);
}

beforeEach(() => {
  vi.resetModules();
  localStorage.clear();
  hoisted.axiosPost.mockReset();
  hoisted.mockGetState.mockReset();
  hoisted.mockSetState.mockReset();
  hoisted.mockGetState.mockReturnValue({
    isAuthenticated: true,
    user: { id: 1, username: 'test', email: 't@example.com', roles: ['USER'] },
    logout: makeLogoutMock(),
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('client.ts response interceptor - token refresh', () => {
  it('Scenario 1 - single 401: refreshes exactly once, retries with the new token, caller resolves', async () => {
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');

    hoisted.axiosPost.mockResolvedValue({
      data: { accessToken: 'new-access-token', refreshToken: 'new-refresh-token' },
    });
    hoisted.mockApiInstance.mockResolvedValue({ data: 'retried-response' });

    const result = await handler(make401Error('/protected'));

    expect(hoisted.axiosPost).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('accessToken')).toBe('new-access-token');
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh-token');
    expect(hoisted.mockApiInstance).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/protected',
        headers: expect.objectContaining({ Authorization: 'Bearer new-access-token' }),
      })
    );
    // user is truthy in the default mocked auth state -> the store must be updated too.
    expect(hoisted.mockSetState).toHaveBeenCalledWith({
      accessToken: 'new-access-token',
      isAuthenticated: true,
    });
    expect(result).toEqual({ data: 'retried-response' });
  });

  it('Scenario 2 - concurrent 401s: exactly one refresh, both requests retry with the new token and resolve independently', async () => {
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');

    const refresh = deferred<{ data: { accessToken: string; refreshToken: string } }>();
    hoisted.axiosPost.mockReturnValue(refresh.promise);
    hoisted.mockApiInstance.mockImplementation((config: { url: string }) => {
      if (config.url === '/a') return Promise.resolve({ data: 'A-response' });
      if (config.url === '/b') return Promise.resolve({ data: 'B-response' });
      throw new Error(`unexpected retry for ${config.url}`);
    });

    // Both handlers start before the refresh settles - request A triggers the refresh,
    // request B must observe isRefreshing===true (set synchronously before the first
    // await inside the handler) and queue instead of starting a second refresh.
    const promiseA = handler(make401Error('/a'));
    const promiseB = handler(make401Error('/b'));

    expect(hoisted.axiosPost).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).not.toHaveBeenCalled();

    refresh.resolve({ data: { accessToken: 'new-access-token', refreshToken: 'new-refresh-token' } });

    const [resultA, resultB] = await Promise.all([promiseA, promiseB]);

    expect(hoisted.axiosPost).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).toHaveBeenCalledTimes(2);
    expect(hoisted.mockApiInstance).toHaveBeenCalledWith(
      expect.objectContaining({ url: '/a', headers: expect.objectContaining({ Authorization: 'Bearer new-access-token' }) })
    );
    expect(hoisted.mockApiInstance).toHaveBeenCalledWith(
      expect.objectContaining({ url: '/b', headers: expect.objectContaining({ Authorization: 'Bearer new-access-token' }) })
    );
    expect(resultA).toEqual({ data: 'A-response' });
    expect(resultB).toEqual({ data: 'B-response' });
  });

  it('Scenario 3 - stale-token race: retries immediately with the current token, no refresh attempted', async () => {
    const handler = await loadClientAndCaptureHandler();
    // The failed request carried an older token than what is now current in localStorage -
    // another concurrent request must have already completed a refresh.
    localStorage.setItem('accessToken', 'current-access-token');
    localStorage.setItem('refreshToken', 'current-refresh-token');

    hoisted.mockApiInstance.mockResolvedValue({ data: 'retried-with-current-token' });

    const result = await handler(make401Error('/protected', 'Bearer old-access-token'));

    expect(hoisted.axiosPost).not.toHaveBeenCalled();
    expect(hoisted.mockApiInstance).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/protected',
        headers: expect.objectContaining({ Authorization: 'Bearer current-access-token' }),
      })
    );
    expect(result).toEqual({ data: 'retried-with-current-token' });
  });

  it('Scenario 4 - refresh failure: triggering and queued requests both reject, logout is invoked, a later 401 can start a new refresh cycle', async () => {
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');
    const logoutMock = makeLogoutMock();
    hoisted.mockGetState.mockReturnValue({
      isAuthenticated: true,
      user: { id: 1, username: 'test', email: 't@example.com', roles: ['USER'] },
      logout: logoutMock,
    });
    const replaceStateSpy = vi.spyOn(window.history, 'replaceState');
    const dispatchEventSpy = vi.spyOn(window, 'dispatchEvent');

    const refresh = deferred<never>();
    hoisted.axiosPost.mockReturnValueOnce(refresh.promise);

    const promiseA = handler(make401Error('/a'));
    const promiseB = handler(make401Error('/b'));

    const refreshError = new Error('refresh failed');
    refresh.reject(refreshError);

    await expect(promiseA).rejects.toBe(refreshError);
    await expect(promiseB).rejects.toBe(refreshError);

    expect(hoisted.axiosPost).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).not.toHaveBeenCalled(); // no retry was ever attempted
    expect(logoutMock).toHaveBeenCalledTimes(1);
    expect(replaceStateSpy).toHaveBeenCalledWith(null, '', '/login');
    expect(dispatchEventSpy).toHaveBeenCalledWith(expect.any(PopStateEvent));

    // isRefreshing must have been reset (in the interceptor's `finally`) so an unrelated,
    // later 401 correctly starts its own new refresh cycle rather than queuing forever.
    hoisted.axiosPost.mockResolvedValueOnce({
      data: { accessToken: 'second-cycle-token', refreshToken: 'second-cycle-refresh' },
    });
    hoisted.mockApiInstance.mockResolvedValueOnce({ data: 'second-cycle-response' });
    const resultC = await handler(make401Error('/c'));

    expect(hoisted.axiosPost).toHaveBeenCalledTimes(2);
    expect(resultC).toEqual({ data: 'second-cycle-response' });
  });

  it('Scenario 5 - refresh recursion guard: a 401 from the refresh call itself does not recurse, completes deterministically', async () => {
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');
    const logoutMock = makeLogoutMock();
    hoisted.mockGetState.mockReturnValue({
      isAuthenticated: true,
      user: { id: 1, username: 'test', email: 't@example.com', roles: ['USER'] },
      logout: logoutMock,
    });

    const refreshEndpointError = { response: { status: 401 }, config: { url: '/api/auth/refresh' } };
    hoisted.axiosPost.mockRejectedValue(refreshEndpointError);

    await expect(handler(make401Error('/protected'))).rejects.toBe(refreshEndpointError);

    // Bounded call count is the proof of no recursive loop: axios.post (the refresh call)
    // is a raw, un-intercepted call, so a 401 on IT can never re-enter this same handler.
    expect(hoisted.axiosPost).toHaveBeenCalledTimes(1);
    expect(hoisted.mockApiInstance).not.toHaveBeenCalled();
    expect(logoutMock).toHaveBeenCalledTimes(1);
  });

  it('Scenario 6 - non-401 error: rejected unchanged, no refresh attempted', async () => {
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');

    const serverError = makeStatusError(500);

    await expect(handler(serverError)).rejects.toBe(serverError);

    expect(hoisted.axiosPost).not.toHaveBeenCalled();
    expect(hoisted.mockApiInstance).not.toHaveBeenCalled();
  });

  it('Scenario 7 - unauthenticated: rejects immediately, refresh never attempted, logout not invoked by this branch', async () => {
    const logoutMock = makeLogoutMock();
    hoisted.mockGetState.mockReturnValue({
      isAuthenticated: false,
      user: null,
      logout: logoutMock,
    });
    const handler = await loadClientAndCaptureHandler();
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');

    const error = make401Error('/protected');

    await expect(handler(error)).rejects.toBe(error);

    expect(hoisted.axiosPost).not.toHaveBeenCalled();
    expect(hoisted.mockApiInstance).not.toHaveBeenCalled();
    expect(logoutMock).not.toHaveBeenCalled();
  });
});
