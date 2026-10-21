import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/** KYC-01: the admin user list and card show KYC and the trusted status as two separate facts. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const users = [
  { id: 1, username: 'verified_untrusted', email: 'a@t', active: true, trusted: false, roles: ['ROLE_USER'], identityStatus: 'VERIFIED' },
  { id: 2, username: 'rejected_trusted', email: 'b@t', active: true, trusted: true, roles: ['ROLE_USER'], identityStatus: 'REJECTED' },
  { id: 3, username: 'never_submitted', email: 'c@t', active: true, trusted: false, roles: ['ROLE_USER'], identityStatus: null },
];

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) =>
    url.startsWith('/admin/users') ? Promise.resolve({ data: users }) : Promise.resolve({ data: [] }));
});
afterEach(() => { vi.restoreAllMocks(); });

async function renderUsers() {
  const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/users'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('verified_untrusted');
}

const row = (name: string) => screen.getByText(name).closest('tr')!;

describe('KYC-01: admin users - KYC and trust side by side', () => {
  it('the list has a KYC column next to the trust column, each with its own value', async () => {
    await renderUsers();
    expect(screen.getAllByRole('columnheader').map(h => h.textContent)).toEqual(expect.arrayContaining(['KYC', 'Доверие']));
    expect(row('verified_untrusted')).toHaveTextContent('Подтверждено');
    expect(row('verified_untrusted')).toHaveTextContent('Не предоставлено');
    expect(row('rejected_trusted')).toHaveTextContent('Отклонено');
    expect(row('rejected_trusted')).toHaveTextContent('Предоставлено');
    expect(row('never_submitted')).toHaveTextContent('Не подавалась');
    expect(screen.queryByText('Не рассмотрен')).toBeNull();
  });

  it('the user card states KYC and trust separately and that they are independent', async () => {
    await renderUsers();
    fireEvent.click(within(row('verified_untrusted')).getByText('Просмотр'));
    const card = (await screen.findByText('KYC:')).closest('div')!.parentElement!;
    expect(card).toHaveTextContent('KYC:Подтверждено');
    expect(card).toHaveTextContent('Доверие:Не предоставлено');
    expect(card).toHaveTextContent('подтверждение личности не делает пользователя доверенным');
  });
});
