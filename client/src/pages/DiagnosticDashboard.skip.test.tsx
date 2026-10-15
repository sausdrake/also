import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { DiagnosticDashboard } from './DiagnosticDashboard';
import api from '../api/client';

/**
 * Scenario 3 step 4: a non-required item can be skipped ("Пропустить") and the skip is sent to
 * the server as `skipped`; a required item offers no skip; a stored skip is shown on reload and
 * can be undone. Rendered through DiagnosticDashboard's own routing (My reports -> Заполнить).
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const template = {
  id: 3, name: 'Седан', vehicleType: 'SEDAN',
  groups: [{
    id: 30, name: 'Кузов', sortOrder: 1,
    items: [
      { id: 301, title: 'Состояние ЛКП', fieldType: 'TEXT', isRequired: true, hasPhoto: false, hasVideo: false, hasRating: false },
      { id: 302, title: 'Доп. замечания', fieldType: 'TEXT', isRequired: false, hasPhoto: false, hasVideo: false, hasRating: false },
    ],
  }],
};

function mockApi(existingAnswers: unknown[] = []) {
  apiGet.mockImplementation((url: string) => {
    if (url === '/diagnostic/work/my?size=100') {
      return Promise.resolve({ data: { content: [{ id: 5, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001', status: 'IN_PROGRESS', createdAt: '2026-10-15T00:00:00' }] } });
    }
    if (url === '/diagnostic/work/5') {
      return Promise.resolve({ data: { id: 5, status: 'IN_PROGRESS', templateId: 3, template, existingAnswers } });
    }
    return Promise.resolve({ data: [] });
  });
}

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openReport() {
  const router = createMemoryRouter(
    [{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }],
    { initialEntries: ['/diagnostic/reports'] }
  );
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('Заполнить'));
  await screen.findByText('Доп. замечания');
}

function sentAnswers() {
  const call = apiPost.mock.calls.find(c => c[0] === '/diagnostic/work/5/answers');
  return call?.[1].answers as Array<Record<string, unknown>>;
}

describe('DiagnosticDashboard report form - skip a non-required item (scenario 3.4)', () => {
  it('offers "Пропустить" only for the non-required item', async () => {
    mockApi();
    await openReport();

    expect(screen.getByLabelText('Пропустить пункт Доп. замечания')).toBeInTheDocument();
    expect(screen.queryByLabelText('Пропустить пункт Состояние ЛКП')).not.toBeInTheDocument();
  });

  it('a skipped item is shown as skipped and sent as skipped=true', async () => {
    mockApi();
    await openReport();

    fireEvent.click(screen.getByLabelText('Пропустить пункт Доп. замечания'));
    expect(screen.getByText('Пункт пропущен')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Сохранить черновик'));

    await waitFor(() => expect(sentAnswers()).toBeDefined());
    const skipped = sentAnswers().find(a => a.itemId === 302)!;
    expect(skipped.skipped).toBe(true);
    expect(skipped).not.toHaveProperty('isSkipped');
  });

  it('a stored skip is shown on reload and can be undone', async () => {
    mockApi([{ inspectionItemId: 302, rating: null, comment: null, photoUrls: [], videoUrls: [], skipped: true }]);
    await openReport();

    expect(screen.getByText('Пункт пропущен')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Вернуть пункт Доп. замечания'));
    expect(screen.queryByText('Пункт пропущен')).not.toBeInTheDocument();
    fireEvent.click(screen.getByText('Сохранить черновик'));

    await waitFor(() => expect(sentAnswers()).toBeDefined());
    expect(sentAnswers().find(a => a.itemId === 302)!.skipped).toBe(false);
  });
});
