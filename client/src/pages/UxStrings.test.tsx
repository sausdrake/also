import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import { SellerViolationResolutionPanel } from '../components/admin/diagnostic/SellerViolationResolutionPanel';
import { IdentityReviewPanel } from '../components/admin/identity/IdentityReviewPanel';
import api from '../api/client';

/** FE-UX-01: no raw technical strings where a person reads them (roles, violation codes, KYC status). */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset();
});

describe('FE-UX-01: readable admin strings', () => {
  it('the users list shows role names, not ROLE_* / ADMIN', async () => {
    apiGet.mockImplementation((url: string) => Promise.resolve({ data: url.startsWith('/admin/users') ? [
      { id: 1, username: 'boss', email: 'b@test.com', active: true, trusted: true, roles: ['ROLE_ADMIN'] },
      { id: 2, username: 'diag', email: 'd@test.com', active: true, trusted: false, roles: ['ROLE_DIAGNOSTIC', 'ROLE_USER'] },
    ] : [] }));
    render(<RouterProvider router={createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/users'] })} />);
    const row = (await screen.findByText('diag')).closest('tr')!;
    expect(within(row).getByText('Диагност')).toBeInTheDocument();
    expect(within(row).getByText('Пользователь')).toBeInTheDocument();
    expect(within((await screen.findByText('boss')).closest('tr')!).getByText('Администратор')).toBeInTheDocument();
    expect(screen.queryByText(/^(ADMIN|DIAGNOSTIC|USER|ROLE_[A-Z]+)$/)).not.toBeInTheDocument();
  });

  it('violations show their description first, the code only as a reference', async () => {
    apiGet.mockResolvedValue({ data: [{ id: 1, code: 'MISSING_VIN_PHOTO', description: 'Отсутствует фото VIN' }, { id: 2, code: 'NO_DESC', description: '' }] });
    render(<SellerViolationResolutionPanel requestId={42} commitmentStatus="AWAITING_FINALIZATION" violationResolution={null} onDone={vi.fn()} />);
    const box = await screen.findByRole('checkbox', { name: /Отсутствует фото VIN/ });
    expect(box).toBeInTheDocument();
    expect(screen.getByText('Отсутствует фото VIN')).toBeVisible();
    expect(screen.getByRole('checkbox', { name: 'NO_DESC' })).toBeInTheDocument();
  });

  it('the identity review shows the status label, not the enum', () => {
    apiGet.mockResolvedValue({ data: [] });
    render(<IdentityReviewPanel onActioned={vi.fn()} item={{ id: 3, userId: 9, status: 'PENDING_REVIEW', issuingCountry: 'KG', documentType: 'NATIONAL_ID',
      canonicalDocumentNumber: 'AN1234567', createdAt: '2026-10-20T10:00:00Z', updatedAt: '2026-10-20T10:00:00Z', selfieProvided: true }} />);
    expect(screen.getByText('KYC: На рассмотрении')).toBeInTheDocument();   // KYC-01: labelled as KYC next to the trust line
    expect(screen.queryByText('PENDING_REVIEW')).not.toBeInTheDocument();
  });
});
