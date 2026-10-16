import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import App from './App';
import { useAuthStore } from './store/authStore';

/**
 * FE-04: when a route chunk cannot be fetched (network error, or a deploy replaced the files an
 * open tab still refers to), the user sees a clear message with "reload" - the header stays, the
 * app does not go blank, and moving to another area recovers.
 */

vi.mock('./pages/ProfilePage', () => {
  throw new Error('Failed to fetch dynamically imported module: /assets/ProfilePage-abc.js');
});
vi.mock('./pages/HomePage', () => ({ HomePage: () => <div>HOME-PAGE</div> }));
vi.mock('./hooks/useNotifications', () => ({
  useNotifications: () => ({ notifications: [], unreadCount: 0, markRead: vi.fn(), markAllRead: vi.fn(), reload: vi.fn() }),
}));

function token(): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u', roles: ['ROLE_USER'], exp: Math.floor(Date.now() / 1000) + 3600 })}.sig`;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('a route chunk that fails to load (FE-04)', () => {
  it('shows a recoverable error instead of a blank page; another area works again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const reload = vi.fn();
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, reload, pathname: '/profile' } as Location);
    useAuthStore.setState({
      accessToken: token(), isAuthenticated: true,
      user: { id: 1, username: 'u', email: 'u@example.com', roles: ['ROLE_USER'] },
    } as never);
    window.history.replaceState(null, '', '/profile');
    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить страницу');
    expect(screen.getByRole('link', { name: 'На главную' })).toHaveAttribute('href', '/');
    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }));
    expect(reload).toHaveBeenCalledTimes(1);
    vi.restoreAllMocks();
    vi.spyOn(console, 'error').mockImplementation(() => {});

    act(() => {
      window.history.pushState(null, '', '/');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(await screen.findByText('HOME-PAGE')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
