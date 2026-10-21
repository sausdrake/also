import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';

/**
 * Scenario 18 step 2: the buyer-facing diagnostic report opens with a summary - the overall score
 * (average of the ratings given) and the key defects (items rated 3 or lower), worst first.
 * Skipped items do not count. Scenario 3.4: skipped items read "Пропущен диагностом".
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({ default: { get: vi.fn(() => Promise.resolve({ data: {} })), post: vi.fn() } }));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;

const auction = {
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status: 'ACTIVE', startingPrice: 1000000, currentPrice: 1000000,
  minBidStep: 10000, minimumNextBid: 1010000, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2026-12-31T00:00:00Z',
  confirmedStart: true, photos: [], mainPhotoUrl: null, bidCount: 0,
};

const report = (answers: unknown[]) => ({
  vin: 'WBA00000000000001',
  template: {
    name: 'Седан',
    groups: [
      { id: 1, name: 'Кузов', items: [
        { id: 11, title: 'Состояние ЛКП', fieldType: 'NUMBER' },
        { id: 12, title: 'Коррозия', fieldType: 'NUMBER' },
      ] },
      { id: 2, name: 'Двигатель', items: [
        { id: 21, title: 'Работа двигателя', fieldType: 'NUMBER' },
        { id: 22, title: 'Состояние шин', fieldType: 'NUMBER' },
        { id: 23, title: 'Шумы', fieldType: 'NUMBER' },
      ] },
    ],
  },
  existingAnswers: answers,
});

function mockApi(answers: unknown[]) {
  axiosGet.mockImplementation((url: string) => {
    if (url.includes('/api/public/auctions/1')) return Promise.resolve({ data: auction });
    if (url.includes('/public/reports/by-vehicle/9')) return Promise.resolve({ data: report(answers) });
    return Promise.resolve({ data: {} });
  });
}

beforeEach(() => {
  axiosGet.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openReportTab() {
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('Диагностический отчёт'));
}

const answer = (id: number, rating: number | null, comment = '', skipped = false) =>
  ({ inspectionItemId: id, rating, comment, photoUrls: [], skipped });

describe('AuctionPage diagnostic report summary (scenario 18)', () => {
  it('shows the overall score and the key defects, worst first; skipped items do not count', async () => {
    mockApi([answer(11, 5), answer(12, 3, 'Ржавчина на пороге'), answer(21, 2, 'Стук'), answer(22, null, '', true), answer(23, 4)]);
    await openReportTab();

    const summary = screen.getByTestId('report-summary');
    expect(within(summary).getByText('3.5')).toBeInTheDocument();
    expect(within(summary).getByText(/по 4 оценённым пунктам/)).toBeInTheDocument();
    expect(within(summary).getByText('Ключевые недостатки (2)')).toBeInTheDocument();
    const items = within(summary).getAllByRole('listitem').map(li => li.textContent);
    expect(items[0]).toContain('2/5');
    expect(items[0]).toContain('Двигатель: Работа двигателя');
    expect(items[0]).toContain('Стук');
    expect(items[1]).toContain('3/5');
    expect(items[1]).toContain('Кузов: Коррозия');
    expect(screen.getByText('Пропущен диагностом')).toBeInTheDocument();
  });

  it('no item rated 3 or lower -> "no key defects"; answers without a rating do not count', async () => {
    mockApi([answer(11, 5), answer(12, 4), answer(21, 5), answer(22, null, 'Нет данных')]);
    await openReportTab();

    const summary = screen.getByTestId('report-summary');
    expect(within(summary).getByText('Ключевых недостатков не выявлено')).toBeInTheDocument();
    expect(within(summary).getByText('4.7')).toBeInTheDocument();
    expect(within(summary).getByText(/по 3 оценённым пунктам/)).toBeInTheDocument();
  });

  it('no ratings at all -> no summary', async () => {
    mockApi([]);
    await openReportTab();

    expect(screen.queryByTestId('report-summary')).not.toBeInTheDocument();
  });
});
