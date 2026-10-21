import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { IdentityReviewPanel } from './IdentityReviewPanel';
import { approve, getHistoryAdmin, type IdentityStatusResponse } from '../../../api/identityApi';
import api from '../../../api/client';

/** KYC-01: approving KYC must not look like granting the trusted (bidding) status. */

vi.mock('../../../api/identityApi', () => ({
  approve: vi.fn(), reject: vi.fn(), revoke: vi.fn(),
  getDocumentUrlAdmin: vi.fn(), getHistoryAdmin: vi.fn(), getSelfieUrlAdmin: vi.fn(),
}));
vi.mock('../../../api/client', () => ({ default: { get: vi.fn() } }));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const pending: IdentityStatusResponse = {
  id: 7, userId: 70, status: 'PENDING_REVIEW', issuingCountry: 'KG', documentType: 'NATIONAL_ID',
  canonicalDocumentNumber: 'AN1234567', createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z', selfieProvided: true,
};

beforeEach(() => {
  (getHistoryAdmin as unknown as ReturnType<typeof vi.fn>).mockReset().mockResolvedValue([]);
  (approve as unknown as ReturnType<typeof vi.fn>).mockReset().mockResolvedValue({});
  apiGet.mockReset();
});

describe('KYC-01: identity review shows the trusted status separately', () => {
  it('an untrusted user: "Не предоставлено"; the approve step and the success message say trust is granted separately', async () => {
    apiGet.mockResolvedValue({ data: { id: 70, trusted: false } });
    render(<IdentityReviewPanel item={pending} onActioned={vi.fn()} />);

    expect(await screen.findByText(/Доверие \(участие в торгах\): Не предоставлено/)).toBeInTheDocument();
    expect(apiGet).toHaveBeenCalledWith('/admin/users/70');
    fireEvent.click(screen.getByText('Подтвердить личность'));
    expect(screen.getByText(/не делает пользователя доверенным/)).toBeInTheDocument();

    await act(async () => { fireEvent.click(screen.getByText('Подтвердить одобрение')); });
    expect(await screen.findByText(/Статус доверенного пользователя для торгов не выдан/)).toBeInTheDocument();
  });

  it('an already trusted user: "Предоставлено" and a plain success message', async () => {
    apiGet.mockResolvedValue({ data: { id: 70, trusted: true } });
    render(<IdentityReviewPanel item={pending} onActioned={vi.fn()} />);

    expect(await screen.findByText(/Доверие \(участие в торгах\): Предоставлено/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Подтвердить личность'));
    await act(async () => { fireEvent.click(screen.getByText('Подтвердить одобрение')); });
    expect(await screen.findByText('Личность подтверждена')).toBeInTheDocument();
  });

  it('the trust lookup failing does not block the review (shown as a dash)', async () => {
    apiGet.mockRejectedValue(new Error('down'));
    render(<IdentityReviewPanel item={pending} onActioned={vi.fn()} />);

    expect(await screen.findByText(/Доверие \(участие в торгах\): —/)).toBeInTheDocument();
    expect(screen.getByText('Подтвердить личность')).toBeEnabled();
  });
});
