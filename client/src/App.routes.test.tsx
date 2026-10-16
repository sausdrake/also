import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import App from './App';
import { useAuthStore } from './store/authStore';

/**
 * FE-04: route-level code splitting. The lazy feature pages are replaced by markers whose module
 * factories record when the module is actually loaded - so these tests prove which chunks are
 * requested, not only what is rendered.
 */

const h = vi.hoisted(() => ({
  loads: { auction: 0, profile: 0, admin: 0, diagnostic: 0, changePassword: 0 },
  adminMounts: 0,
}));

vi.mock('./pages/AuctionPage', () => {
  h.loads.auction++;
  return { AuctionPage: () => <div>AUCTION-PAGE</div> };
});
vi.mock('./pages/ProfilePage', () => {
  h.loads.profile++;
  return { ProfilePage: () => <div>PROFILE-PAGE</div> };
});
vi.mock('./pages/ChangePasswordPage', () => {
  h.loads.changePassword++;
  return { ChangePasswordPage: () => <div>CHANGE-PASSWORD-PAGE</div> };
});
vi.mock('./pages/AdminDashboard', () => {
  h.loads.admin++;
  return {
    AdminDashboard: () => {
      useEffect(() => { h.adminMounts++; }, []);
      return <div>ADMIN-PAGE</div>;
    },
  };
});
vi.mock('./pages/DiagnosticDashboard', () => {
  h.loads.diagnostic++;
  return { DiagnosticDashboard: () => <div>DIAGNOSTIC-PAGE</div> };
});
// The shell's live parts are not under test here: no WebSocket, no API calls.
vi.mock('./pages/HomePage', () => ({ HomePage: () => <div>HOME-PAGE</div> }));
vi.mock('./hooks/useNotifications', () => ({
  useNotifications: () => ({ notifications: [], unreadCount: 0, markRead: vi.fn(), markAllRead: vi.fn() }),
}));

// Taken right after App was imported, before anything rendered: no lazy page may be part of the
// initial module graph.
const loadedAtImport = { ...h.loads };

function token(roles: string[]): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u', roles, exp: Math.floor(Date.now() / 1000) + 3600 })}.sig`;
}

function signIn(roles: string[]) {
  useAuthStore.setState({
    accessToken: token(roles), isAuthenticated: true,
    user: { id: 1, username: 'u', email: 'u@example.com', roles },
  } as never);
}

function go(path: string) {
  act(() => {
    window.history.pushState(null, '', path);
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
}

function open(path: string) {
  window.history.replaceState(null, '', path);
  return render(<App />);
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, isAuthenticated: false, user: null } as never);
  h.adminMounts = 0;
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('App routing with lazy feature pages (FE-04)', () => {
  it('importing the app loads none of the five lazy feature pages', () => {
    expect(loadedAtImport).toEqual({ auction: 0, profile: 0, admin: 0, diagnostic: 0, changePassword: 0 });
  });

  it('anonymous deep link to /admin redirects to /login without requesting the admin chunk', async () => {
    const before = h.loads.admin;
    open('/admin/users');
    expect(await screen.findByRole('button', { name: /Войти/ })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');
    expect(h.loads.admin).toBe(before);
  });

  it('a signed-in USER sent to /admin lands on /forbidden, admin chunk never requested', async () => {
    const before = h.loads.admin;
    signIn(['ROLE_USER']);
    open('/admin');
    await screen.findByText(/403|Доступ запрещ/i);
    expect(window.location.pathname).toBe('/forbidden');
    expect(h.loads.admin).toBe(before);
  });

  it('admin deep link (refresh on a nested route) loads the admin page; tabs inside /admin never remount it', async () => {
    signIn(['ROLE_ADMIN']);
    open('/admin/users');
    expect(await screen.findByText('ADMIN-PAGE')).toBeInTheDocument();
    expect(h.adminMounts).toBe(1);
    go('/admin/auctions');
    go('/admin/settings');
    expect(screen.getByText('ADMIN-PAGE')).toBeInTheDocument();
    expect(h.adminMounts).toBe(1);
  });

  it('public auction deep link works without signing in', async () => {
    open('/auction/42');
    expect(await screen.findByText('AUCTION-PAGE')).toBeInTheDocument();
  });

  it('navigation between lazy areas renders each page; the landing page needs no chunk', async () => {
    signIn(['ROLE_USER']);
    open('/');
    expect(screen.getByText('HOME-PAGE')).toBeInTheDocument();
    go('/profile');
    expect(await screen.findByText('PROFILE-PAGE')).toBeInTheDocument();
    go('/auction/7');
    expect(await screen.findByText('AUCTION-PAGE')).toBeInTheDocument();
    go('/change-password');
    expect(await screen.findByText('CHANGE-PASSWORD-PAGE')).toBeInTheDocument();
    go('/');
    expect(screen.getByText('HOME-PAGE')).toBeInTheDocument();
  });

  it('diagnostician deep link loads the diagnostic page', async () => {
    signIn(['ROLE_DIAGNOSTIC']);
    open('/diagnostic');
    expect(await screen.findByText('DIAGNOSTIC-PAGE')).toBeInTheDocument();
  });
});
