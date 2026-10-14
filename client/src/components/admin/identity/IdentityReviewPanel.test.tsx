import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { IdentityReviewPanel } from './IdentityReviewPanel';
import { getHistoryAdmin, getSelfieUrlAdmin, type IdentityStatusResponse } from '../../../api/identityApi';

/**
 * MVP-004: the administrator opens the live selfie next to the document; a submission made before
 * the selfie became mandatory offers no approval - only rejection, so the user resubmits.
 */

vi.mock('../../../api/identityApi', () => ({
  approve: vi.fn(),
  reject: vi.fn(),
  revoke: vi.fn(),
  getDocumentUrlAdmin: vi.fn(),
  getHistoryAdmin: vi.fn(),
  getSelfieUrlAdmin: vi.fn(),
}));

const mockedHistory = getHistoryAdmin as unknown as ReturnType<typeof vi.fn>;
const mockedSelfieUrl = getSelfieUrlAdmin as unknown as ReturnType<typeof vi.fn>;

function pending(overrides: Partial<IdentityStatusResponse> = {}): IdentityStatusResponse {
  return {
    id: 7, userId: 70, status: 'PENDING_REVIEW', issuingCountry: 'KG', documentType: 'NATIONAL_ID',
    canonicalDocumentNumber: 'AN1234567', createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z',
    selfieProvided: true, ...overrides,
  };
}

beforeEach(() => {
  mockedHistory.mockReset().mockResolvedValue([]);
  mockedSelfieUrl.mockReset().mockResolvedValue('https://storage/selfie?signed=1');
  vi.spyOn(window, 'open').mockReturnValue(null);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('IdentityReviewPanel (MVP-004 live selfie review)', () => {
  it('opens the live selfie of the submission', async () => {
    render(<IdentityReviewPanel item={pending()} onActioned={vi.fn()} />);

    fireEvent.click(screen.getByText('Просмотреть селфи'));

    await waitFor(() => expect(window.open).toHaveBeenCalledWith('https://storage/selfie?signed=1', '_blank', 'noopener'));
    expect(mockedSelfieUrl).toHaveBeenCalledWith(7);
    expect(screen.getByText('Подтвердить личность')).toBeInTheDocument();
  });

  it('a submission without a selfie cannot be approved, only rejected', () => {
    render(<IdentityReviewPanel item={pending({ selfieProvided: false })} onActioned={vi.fn()} />);

    expect(screen.getByText(/нет живого селфи/)).toBeInTheDocument();
    expect(screen.queryByText('Просмотреть селфи')).not.toBeInTheDocument();
    expect(screen.queryByText('Подтвердить личность')).not.toBeInTheDocument();
    expect(screen.getByText('Отклонить')).toBeInTheDocument();
  });
});
