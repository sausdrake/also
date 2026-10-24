import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { CancelAuctionModal } from './CancelAuctionModal';

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('../../api/client', () => ({
  default: { get: (...a: unknown[]) => apiGet(...a), post: (...a: unknown[]) => apiPost(...a) },
  BASE_URL: 'http://api.test',
}));

beforeEach(() => { apiGet.mockReset(); apiPost.mockReset(); });

describe('AUC-NOSALE-01: cancelling a lot', () => {
  it('without seller fault: cancels with no violations (guarantee returned in full)', async () => {
    apiPost.mockResolvedValue({ data: {} });
    const onDone = vi.fn();
    render(<CancelAuctionModal auctionId={7} onDone={onDone} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/auctions/7/cancel', undefined));
    expect(onDone).toHaveBeenCalledWith('Аукцион #7 отменён');
    expect(apiGet).not.toHaveBeenCalled();
  });

  it('for a confirmed seller violation: a violation must be chosen and its id is sent', async () => {
    apiGet.mockResolvedValue({ data: [{ id: 4, code: 'SELLER_UNREACHABLE', description: 'Продавец недоступен' }] });
    apiPost.mockResolvedValue({ data: {} });
    render(<CancelAuctionModal auctionId={7} onDone={() => {}} onClose={() => {}} />);
    fireEvent.click(screen.getByLabelText(/Подтверждённое нарушение продавца/));
    const confirm = screen.getByRole('button', { name: 'Подтвердить' });
    expect(confirm).toBeDisabled();
    fireEvent.click(await screen.findByRole('checkbox'));
    fireEvent.click(confirm);
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/auctions/7/cancel', { violationDefinitionIds: [4] }));
  });

  it('a refusal is reported as an error', async () => {
    apiPost.mockRejectedValue({ response: { data: { message: 'Невозможно отменить аукцион в статусе SOLD' } } });
    const onDone = vi.fn();
    render(<CancelAuctionModal auctionId={7} onDone={onDone} onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('Невозможно отменить аукцион в статусе SOLD', true));
  });
});
