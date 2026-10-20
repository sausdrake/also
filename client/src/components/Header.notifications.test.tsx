import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { Header } from './Header';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';
import { useNotifications } from '../hooks/useNotifications';

/**
 * FE-NOTIF-01: the bell and inbox end to end (real hook + panel; HTTP client and STOMP mocked).
 * Before: the badge counted unread among the 20 loaded items, every error was swallowed into
 * "Нет уведомлений", DIAGNOSTIC links sent sellers to /diagnostic (-> /forbidden), and the panel
 * was a fixed 384px box.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const stomp = vi.hoisted(() => ({ onMessage: null as null | ((frame: { body: string }) => void) }));
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    private config: { onConnect?: () => void };
    constructor(config: { onConnect?: () => void }) { this.config = config; }
    activate() { this.config.onConnect?.(); }
    deactivate() {}
    subscribe(_dest: string, cb: (frame: { body: string }) => void) { stomp.onMessage = cb; return { unsubscribe() {} }; }
  },
}));
vi.mock('sockjs-client', () => ({ default: class {} }));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPatch = api.patch as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

const item = (id: number, extra: Record<string, unknown> = {}) => ({
  id, userId: 5, type: 'OUTBID', title: `Уведомление ${id}`, message: `Текст ${id}`, read: false,
  createdAt: '2026-10-20T10:00:00', referenceId: 40 + id, referenceType: 'AUCTION', ...extra,
});

type Answer = unknown;
/** Successive answers for the list and the unread count (the last one repeats). */
function mockInbox(lists: Answer[], counts: Answer[]) {
  let li = 0; let ci = 0;
  apiGet.mockImplementation((url: string) => {
    const pick = (arr: Answer[], i: number) => arr[Math.min(i, arr.length - 1)];
    const a = url === '/notifications' ? pick(lists, li++) : url === '/notifications/unread-count' ? pick(counts, ci++) : {};
    return a instanceof Error ? Promise.reject(a) : Promise.resolve({ data: a });
  });
}
const page = (...items: unknown[]) => ({ content: items });

function renderHeader() {
  const router = createMemoryRouter([
    { path: '/', element: <Header /> },
    { path: '/profile/requests', element: <p>SELLER REQUESTS</p> },
    { path: '/auction/:id', element: <p>LOT PAGE</p> },
  ], { initialEntries: ['/'] });
  render(<RouterProvider router={router} />);
}

const bell = () => screen.getByRole('button', { name: /^Уведомления/ });
const openPanel = () => { fireEvent.click(bell()); return screen.getByRole('dialog', { name: 'Уведомления' }); };

beforeEach(() => {
  // a structurally valid, unexpired access token (the background poll waits for one)
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).replace(/=+$/, '');
  localStorage.setItem('accessToken', `h.${payload}.s`);
  useAuthStore.setState({ isAuthenticated: true, accessToken: 'x', user: { id: 5, username: 'seller', email: 's@test.com', roles: ['ROLE_USER'] } });
  apiGet.mockReset();
  apiPatch.mockReset().mockResolvedValue({ data: '' });
  stomp.onMessage = null;
});

afterEach(() => {
  useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null });
  localStorage.clear();
});

describe('FE-NOTIF-01: inbox', () => {
  it('the badge shows the backend unread count, not the unread among the loaded page', async () => {
    mockInbox([page(item(1), item(2, { read: true }))], [{ count: 25 }]);
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 25'));
    expect(within(bell()).getByText('9+')).toBeInTheDocument();
  });

  it('a successful empty inbox says "Нет уведомлений"', async () => {
    mockInbox([page()], [{ count: 0 }]);
    renderHeader();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/notifications/unread-count'));
    expect(await within(openPanel()).findByText('Нет уведомлений')).toBeInTheDocument();
  });

  it('a seller opening DIAG_ACCEPTED goes to their requests (not /diagnostic)', async () => {
    mockInbox([page(item(1, { type: 'DIAG_ACCEPTED', title: 'Заявка принята диагностом', referenceId: 7, referenceType: 'DIAGNOSTIC' }))], [{ count: 1 }]);
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 1'));
    const panel = openPanel();
    fireEvent.click(within(panel).getByText('Заявка принята диагностом'));
    expect(within(panel).getByRole('link', { name: 'Перейти →' })).toHaveAttribute('href', '/profile/requests');
    fireEvent.click(within(panel).getByRole('link', { name: 'Перейти →' }));
    expect(await screen.findByText('SELLER REQUESTS')).toBeInTheDocument();
  });

  it('opening an unread item marks it read once and decrements the backend count', async () => {
    mockInbox([page(item(1))], [{ count: 25 }]);
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 25'));
    const panel = openPanel();
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 24'));
    expect(apiPatch).toHaveBeenCalledTimes(1);
    expect(apiPatch).toHaveBeenCalledWith('/notifications/1/read');
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    expect(apiPatch).toHaveBeenCalledTimes(1);
  });
});

describe('FE-NOTIF-01 adversarial', () => {
  it('a double click while the PATCH is in flight sends one request', async () => {
    mockInbox([page(item(1))], [{ count: 3 }]);
    let release: () => void = () => {};
    apiPatch.mockImplementation(() => new Promise(r => { release = () => r({ data: '' }); }));
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 3'));
    const panel = openPanel();
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    expect(apiPatch).toHaveBeenCalledTimes(1);
    await act(async () => release());
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 2'));
  });

  it('markRead of an already-read notification (hook API) does not decrement the count', async () => {
    mockInbox([page(item(1, { read: true }), item(2))], [{ count: 7 }]);
    const { result } = renderHook(() => useNotifications(), { wrapper: ({ children }) => <>{children}</> });
    await waitFor(() => expect(result.current.unreadCount).toBe(7));
    await act(async () => { await result.current.markRead(1); });
    expect(apiPatch).toHaveBeenCalledWith('/notifications/1/read');
    expect(result.current.unreadCount).toBe(7);
    await act(async () => { await result.current.markRead(2); });
    expect(result.current.unreadCount).toBe(6);
  });

  it.each([
    ['500 on the list', httpError(500, { message: 'Произошла непредвиденная ошибка' }), { count: 1 }, 'Произошла непредвиденная ошибка'],
    ['malformed list', { content: 'nope' }, { count: 1 }, 'Не удалось загрузить уведомления'],
    ['malformed count', { content: [] }, { count: '3' }, 'Не удалось загрузить уведомления'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), { count: 0 }, 'Нет связи с сервером'],
    ['401', httpError(401, { error: 'Unauthorized' }), { count: 0 }, 'Требуется вход в систему.'],
  ])('%s -> error with retry, never "Нет уведомлений"', async (_n, list, count, text) => {
    mockInbox([list], [count]);
    renderHeader();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/notifications', { params: { page: 0, size: 20 } }));
    const panel = openPanel();
    expect(await within(panel).findByRole('alert')).toHaveTextContent(text);
    expect(within(panel).queryByText('Нет уведомлений')).not.toBeInTheDocument();
  });

  it('retry after a failed load shows the inbox', async () => {
    mockInbox([httpError(500, { message: 'Произошла непредвиденная ошибка' }), page(item(3))], [{ count: 1 }]);
    renderHeader();
    const panel = openPanel();
    fireEvent.click(within(await within(panel).findByRole('alert')).getByRole('button', { name: 'Повторить' }));
    expect(await within(panel).findByText('Уведомление 3')).toBeInTheDocument();
    expect(within(panel).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a failed mark-read keeps the item unread and the count, and says so', async () => {
    mockInbox([page(item(1))], [{ count: 4 }]);
    apiPatch.mockRejectedValue(httpError(500, { message: 'Произошла непредвиденная ошибка' }));
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 4'));
    const panel = openPanel();
    fireEvent.click(within(panel).getByText('Уведомление 1'));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('Произошла непредвиденная ошибка');
    expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 4');
    expect(within(panel).getByLabelText('Не прочитано')).toBeInTheDocument();
  });

  it('a failed "Прочитать все" keeps the count and says so', async () => {
    mockInbox([page(item(1))], [{ count: 4 }]);
    apiPatch.mockRejectedValue(httpError(403, { error: 'Forbidden' }));
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 4'));
    const panel = openPanel();
    fireEvent.click(within(panel).getByRole('button', { name: 'Прочитать все' }));
    expect(await within(panel).findByRole('alert')).toHaveTextContent('Недостаточно прав для этого действия.');
    expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 4');
  });

  it('"Прочитать все" zeroes the badge and re-reads the authoritative count', async () => {
    mockInbox([page(item(1))], [{ count: 4 }, { count: 1 }]);
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 4'));
    fireEvent.click(within(openPanel()).getByRole('button', { name: 'Прочитать все' }));
    expect(apiPatch).toHaveBeenCalledWith('/notifications/read-all');
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 1'));
  });

  it('STOMP: a new notification is added and counted once; duplicates, junk and loaded ids are ignored', async () => {
    mockInbox([page(item(1))], [{ count: 1 }]);
    renderHeader();
    await waitFor(() => expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 1'));
    expect(stomp.onMessage).not.toBeNull();
    const push = (body: string) => act(() => stomp.onMessage!({ body }));
    push(JSON.stringify(item(9, { title: 'Пришло по WS' })));
    push(JSON.stringify(item(9, { title: 'Пришло по WS' })));
    push('not json {');
    push(JSON.stringify({ title: 'no id' }));
    push(JSON.stringify(item(1)));
    push(JSON.stringify(item(10, { read: true, title: 'Уже прочитано' })));
    expect(bell()).toHaveAccessibleName('Уведомления, непрочитанных: 2');
    const panel = openPanel();
    expect(within(panel).getAllByText('Пришло по WS')).toHaveLength(1);
    expect(within(panel).getByText('Уже прочитано')).toBeInTheDocument();
  });
});
