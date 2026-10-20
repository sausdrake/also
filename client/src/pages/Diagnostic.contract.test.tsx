import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { DiagnosticDashboard } from './DiagnosticDashboard';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * FE-CONTRACT-01: the diagnostician reads the rejection reason from `rejectedReason` (the backend
 * field; the UI read a never-sent `rejectionComment`) and sees no "Черновик" status (there is no DRAFT
 * in DiagnosticRequestStatus). The admin report review loads the media of video items only.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../components/admin/diagnostic/SellerViolationResolutionPanel', () => ({ SellerViolationResolutionPanel: () => null }));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const row = (id: number, status: string, extra: Record<string, unknown> = {}) => ({
  id, brand: 'BMW', model: `X${id}`, year: 2020, vin: `WBA0000000000000${id}`, status, createdAt: '2026-10-20T10:00:00', ...extra,
});

beforeEach(() => {
  apiGet.mockReset();
});

describe('FE-CONTRACT-01: diagnostician reports', () => {
  function renderReports(rows: unknown[]) {
    apiGet.mockImplementation((url: string) => Promise.resolve({ data: url.startsWith('/diagnostic/work/my') ? { content: rows } : [] }));
    render(<RouterProvider router={createMemoryRouter([{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }],
      { initialEntries: ['/diagnostic/reports'] })} />);
  }

  it('the rejection reason comes from rejectedReason', async () => {
    renderReports([row(1, 'REJECTED', { rejectedReason: 'Нет фото днища' })]);
    expect(await screen.findByText('Нет фото днища')).toBeInTheDocument();
  });

  it('there is no "Черновик" filter, and statuses use the domain labels (unknown -> raw)', async () => {
    renderReports([row(1, 'IN_PROGRESS'), row(2, 'COMPLETED'), row(3, 'DRAFT')]);
    await screen.findByText('BMW X1');
    expect(screen.queryByRole('button', { name: 'Черновик' })).not.toBeInTheDocument();
    expect(screen.getByText('В работе', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getAllByText('На проверке').length).toBeGreaterThan(0);
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
  });

  it('the dashboard "in progress" count counts only IN_PROGRESS', async () => {
    apiGet.mockImplementation((url: string) => Promise.resolve({ data: url.startsWith('/diagnostic/work/my')
      ? { content: [row(1, 'IN_PROGRESS'), row(2, 'DRAFT'), row(3, 'IN_PROGRESS')] } : [] }));
    render(<RouterProvider router={createMemoryRouter([{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }],
      { initialEntries: ['/diagnostic/dashboard'] })} />);
    const card = (await screen.findByText('Отчёты в работе')).parentElement!;
    expect(within(card).getByText('2')).toBeInTheDocument();
  });
});

describe('FE-CONTRACT-01: admin report review shows video item media', () => {
  it('only items with hasVideo load media', async () => {
    const detail = {
      id: 5, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000005', status: 'COMPLETED', createdAt: '2026-10-20T10:00:00',
      template: { name: 'Седан', groups: [{ id: 1, name: 'Кузов', items: [
        { id: 101, title: 'Видео осмотра', fieldType: 'TEXTAREA', isRequired: true, hasVideo: true },
        { id: 102, title: 'Состояние ЛКП', fieldType: 'TEXTAREA', isRequired: true, hasVideo: false },
      ] }] },
      existingAnswers: [{ inspectionItemId: 101, rating: null, comment: 'ok', photoUrls: [] }],
    };
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/admin/diagnostic/reports?')) return Promise.resolve({ data: { content: [detail] } });
      if (url === '/admin/diagnostic/reports/5') return Promise.resolve({ data: detail });
      if (url === '/diagnostic/work/5/items/101/media') return Promise.resolve({ data: [] });
      return Promise.resolve({ data: [] });
    });
    render(<RouterProvider router={createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }],
      { initialEntries: ['/admin/diagnostic/reports'] })} />);
    fireEvent.click((await screen.findAllByText('Детали'))[0]);
    expect(await screen.findByText('Видео не загружено')).toBeInTheDocument();
    const mediaCalls = apiGet.mock.calls.map(c => String(c[0])).filter(u => u.includes('/media'));
    expect(mediaCalls).toEqual(['/diagnostic/work/5/items/101/media']);
  });
});
