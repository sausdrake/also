import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { DiagnosticDashboard } from './DiagnosticDashboard';
import api from '../api/client';

/**
 * DIAG-02: the seeded template items are TEXT with hasRating / hasPhoto. The form shows the text answer
 * plus an optional rating and photos, shows saved values again, and requires only what the backend gate
 * requires (the text).
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
      { id: 301, title: 'Состояние ЛКП', fieldType: 'TEXT', isRequired: true, hasPhoto: true, hasVideo: false, hasRating: true },
      { id: 302, title: 'Пробег', fieldType: 'TEXT', isRequired: false, hasPhoto: false, hasVideo: false, hasRating: false },
      { id: 303, title: 'Общая оценка', fieldType: 'NUMBER', isRequired: false, hasPhoto: false, hasVideo: false, hasRating: true },
    ],
  }],
};

function mockApi(existingAnswers: unknown[] = []) {
  apiGet.mockImplementation((url: string) => {
    if (url === '/diagnostic/work/my?size=100') {
      return Promise.resolve({ data: { content: [{ id: 5, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001', status: 'IN_PROGRESS', createdAt: '2026-10-15T00:00:00Z' }] } });
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
  await screen.findByText('Пробег');
}

const itemBox = (title: string) => screen.getByText(title).closest('div.p-4') as HTMLElement;

function sentAnswers() {
  const call = apiPost.mock.calls.find(c => c[0] === '/diagnostic/work/5/answers');
  return call?.[1].answers as Array<Record<string, unknown>>;
}

describe('DIAG-02 - hasRating / hasPhoto on the diagnostician form', () => {
  it('a TEXT item with both flags shows the text answer, an optional rating and photo capture', async () => {
    mockApi();
    await openReport();
    const box = itemBox('Состояние ЛКП');

    expect(within(box).getByPlaceholderText('Ответ (обязателен)')).toBeInTheDocument();
    expect(within(box).getByText('Оценка (1–5, необязательно)')).toBeInTheDocument();
    expect(within(box).getAllByRole('button', { name: /^Оценка [1-5]$/ })).toHaveLength(5);
    expect(within(box).getByText('Сделать фото')).toBeInTheDocument();
  });

  it('an item without flags shows only its field; a NUMBER item shows one (required-style) rating', async () => {
    mockApi();
    await openReport();

    expect(within(itemBox('Пробег')).queryByText(/Оценка \(1–5/)).not.toBeInTheDocument();
    expect(within(itemBox('Пробег')).queryByText('Сделать фото')).not.toBeInTheDocument();
    expect(within(itemBox('Общая оценка')).getAllByText(/^Оценка \(1–5/)).toHaveLength(1);
    expect(within(itemBox('Общая оценка')).getByText('Оценка (1–5)')).toBeInTheDocument();
  });

  it('saved rating and photos are shown again and sent back with the answer', async () => {
    mockApi([{ inspectionItemId: 301, rating: 4, comment: 'Мелкие сколы', photoUrls: ['/uploads/lkp.jpg'], videoUrls: [], skipped: false }]);
    await openReport();
    const box = itemBox('Состояние ЛКП');

    expect(within(box).getByDisplayValue('Мелкие сколы')).toBeInTheDocument();
    expect(within(box).getByRole('button', { name: 'Оценка 4' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(box).getByRole('button', { name: 'Оценка 5' })).toHaveAttribute('aria-pressed', 'false');
    expect(within(box).getByText('Хорошо')).toBeInTheDocument();
    expect(box.querySelector('img[src="/uploads/lkp.jpg"]')).not.toBeNull();

    fireEvent.click(within(box).getByRole('button', { name: 'Оценка 2' }));
    fireEvent.click(screen.getByText('Сохранить черновик'));
    await waitFor(() => expect(sentAnswers()).toBeDefined());
    expect(sentAnswers().find(a => a.itemId === 301)).toMatchObject({ rating: 2, comment: 'Мелкие сколы', photoUrls: ['/uploads/lkp.jpg'] });
  });

  it('a rating alone does not fill a required TEXT item (same rule as the backend gate)', async () => {
    mockApi();
    await openReport();
    const box = itemBox('Состояние ЛКП');
    fireEvent.click(within(box).getByRole('button', { name: 'Оценка 5' }));

    expect(screen.getByText('Отправить на проверку').closest('button')).toBeDisabled();
    fireEvent.change(within(box).getByPlaceholderText('Ответ (обязателен)'), { target: { value: 'Без повреждений' } });
    expect(screen.getByText('Отправить на проверку').closest('button')).not.toBeDisabled();
  });

  it('rating buttons are touch-sized (44 px)', async () => {
    mockApi();
    await openReport();
    for (const b of within(itemBox('Состояние ЛКП')).getAllByRole('button', { name: /^Оценка [1-5]$/ })) {
      expect(b.className).toContain('w-11');
      expect(b.className).toContain('h-11');
    }
  });
});
