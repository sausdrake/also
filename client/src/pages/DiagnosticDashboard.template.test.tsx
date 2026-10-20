import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { DiagnosticDashboard } from './DiagnosticDashboard';
import api from '../api/client';

/**
 * FE-DIAG-01: an accepted request (IN_PROGRESS) has no questionnaire until the diagnostician binds
 * a template through POST /diagnostic/work/{id}/template. Before the fix the form asked for
 * ?templateId (ignored by the backend), the 409 "no template bound" refusal left the diagnostician
 * on "Не удалось загрузить заявку", and the binding endpoint was never called.
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
    items: [{ id: 301, title: 'Состояние ЛКП', fieldType: 'TEXT', isRequired: true, hasPhoto: false, hasVideo: false, hasRating: false }],
  }],
};
const templates = [{ id: 3, name: 'Седан', vehicleType: 'SEDAN' }, { id: 4, name: 'Кроссовер', vehicleType: 'SUV' }];

const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });
const NOT_BOUND = httpError(409, { status: 409, error: 'Conflict', message: 'Для этой заявки ещё не выбран шаблон анкеты' });

interface Setup {
  rowStatus?: string;
  bound?: boolean;
  formError?: unknown;
  formStatus?: string;
  templatesResult?: unknown;
}

function mockApi({ rowStatus = 'IN_PROGRESS', bound = false, formError, formStatus = 'IN_PROGRESS', templatesResult = templates }: Setup = {}) {
  const state = { bound };
  apiGet.mockImplementation((url: string) => {
    if (url === '/diagnostic/work/my?size=100') {
      return Promise.resolve({ data: { content: [{ id: 5, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001', status: rowStatus, createdAt: '2026-10-15T00:00:00' }] } });
    }
    if (url === '/diagnostic/work/templates') {
      return templatesResult instanceof Error ? Promise.reject(templatesResult) : Promise.resolve({ data: templatesResult });
    }
    if (url.startsWith('/diagnostic/work/5')) {
      if (formError) return Promise.reject(formError);
      if (!state.bound) return Promise.reject(NOT_BOUND);
      return Promise.resolve({ data: { id: 5, brand: 'BMW', model: 'X5', year: 2020, status: formStatus, template, existingAnswers: [] } });
    }
    return Promise.resolve({ data: [] });
  });
  return state;
}

async function renderReports() {
  const router = createMemoryRouter(
    [{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }],
    { initialEntries: ['/diagnostic/reports'] },
  );
  render(<RouterProvider router={router} />);
  await screen.findByText('BMW X5');
}

async function openReport() {
  await renderReports();
  fireEvent.click(screen.getByText('Заполнить'));
}

const formGets = () => apiGet.mock.calls.map(c => c[0] as string).filter(u => u.startsWith('/diagnostic/work/5'));
const bindCalls = () => apiPost.mock.calls.filter(c => c[0] === '/diagnostic/work/5/template');

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

describe('FE-DIAG-01: binding the questionnaire template', () => {
  it('an unbound request shows the picker; confirming binds via POST and then loads the form', async () => {
    const state = mockApi();
    apiPost.mockImplementation((url: string) => {
      if (url === '/diagnostic/work/5/template') state.bound = true;
      return Promise.resolve({ data: {} });
    });
    await openReport();

    const select = await screen.findByLabelText('Шаблон анкеты');
    const confirm = screen.getByRole('button', { name: 'Подтвердить шаблон' });
    expect(confirm).toBeDisabled();
    expect(screen.queryByText('Не удалось загрузить заявку')).not.toBeInTheDocument();

    fireEvent.change(select, { target: { value: '3' } });
    fireEvent.click(confirm);

    expect(await screen.findByText('Состояние ЛКП')).toBeInTheDocument();
    expect(bindCalls()).toEqual([['/diagnostic/work/5/template', { templateId: 3 }]]);
    expect(screen.getByText('Седан')).toBeInTheDocument();
    expect(formGets().every(u => u === '/diagnostic/work/5')).toBe(true);
  });

  it('a bound request opens straight on the form: no picker, no ?templateId, template shown read-only', async () => {
    mockApi({ bound: true });
    await openReport();

    expect(await screen.findByText('Состояние ЛКП')).toBeInTheDocument();
    expect(screen.queryByLabelText('Шаблон анкеты')).not.toBeInTheDocument();
    expect(screen.getByText(/Шаблон анкеты:/)).toBeInTheDocument();
    expect(formGets()).toEqual(['/diagnostic/work/5']);
    expect(bindCalls()).toHaveLength(0);
  });

  it('a refused binding shows the backend message and keeps the picker', async () => {
    mockApi();
    apiPost.mockRejectedValue(httpError(409, { message: 'Шаблон неактивен и не может быть выбран: 4' }));
    await openReport();

    fireEvent.change(await screen.findByLabelText('Шаблон анкеты'), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить шаблон' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Шаблон неактивен и не может быть выбран: 4');
    expect(screen.getByLabelText('Шаблон анкеты')).toBeInTheDocument();
    expect(screen.queryByText('Состояние ЛКП')).not.toBeInTheDocument();
  });

  it('no active templates -> an explanation instead of an empty select', async () => {
    mockApi({ templatesResult: [] });
    await openReport();
    expect(await screen.findByText('Нет активных шаблонов анкеты. Обратитесь к администратору.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Подтвердить шаблон' })).not.toBeInTheDocument();
  });

  it('templates that fail to load are reported, not silently empty', async () => {
    mockApi({ templatesResult: httpError(500, { message: 'Произошла непредвиденная ошибка' }) });
    await openReport();
    expect(await screen.findByText('Произошла непредвиденная ошибка')).toBeInTheDocument();
  });
});

describe('FE-DIAG-01 adversarial: other failures and states never open the picker or the editor', () => {
  it('403 (not assigned) shows the backend reason, no picker', async () => {
    mockApi({ formError: httpError(403, { message: 'Вы не назначены на эту заявку' }) });
    await openReport();
    expect(await screen.findByText('Вы не назначены на эту заявку')).toBeInTheDocument();
    expect(screen.queryByLabelText('Шаблон анкеты')).not.toBeInTheDocument();
  });

  it('a network failure is a connection message, no picker', async () => {
    mockApi({ formError: Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }) });
    await openReport();
    expect(await screen.findByText(/Нет связи с сервером/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Шаблон анкеты')).not.toBeInTheDocument();
  });

  it('a bind that succeeds but whose reload fails shows the reload error, not the picker', async () => {
    const state = mockApi();
    apiPost.mockImplementation(() => {
      apiGet.mockImplementation((url: string) => url.startsWith('/diagnostic/work/5')
        ? Promise.reject(httpError(500, { message: 'Произошла непредвиденная ошибка' }))
        : Promise.resolve({ data: templates }));
      state.bound = true;
      return Promise.resolve({ data: {} });
    });
    await openReport();
    fireEvent.change(await screen.findByLabelText('Шаблон анкеты'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить шаблон' }));
    expect(await screen.findByText('Произошла непредвиденная ошибка')).toBeInTheDocument();
    expect(screen.queryByLabelText('Шаблон анкеты')).not.toBeInTheDocument();
  });

  it('a double click binds once', async () => {
    mockApi();
    let release: (v: unknown) => void = () => {};
    apiPost.mockImplementation(() => new Promise(r => { release = r; }));
    await openReport();
    fireEvent.change(await screen.findByLabelText('Шаблон анкеты'), { target: { value: '3' } });
    const confirm = screen.getByRole('button', { name: 'Подтвердить шаблон' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Сохранение…' })).toBeDisabled());
    expect(bindCalls()).toHaveLength(1);
    release({ data: {} });
  });

  it('REJECTED and non-backend statuses (DRAFT, garbage) offer no "Заполнить"', async () => {
    for (const rowStatus of ['REJECTED', 'DRAFT', 'COMPLETED', 'in_progress']) {
      mockApi({ rowStatus });
      const { unmount } = render(<RouterProvider router={createMemoryRouter(
        [{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }], { initialEntries: ['/diagnostic/reports'] })} />);
      await screen.findByText('BMW X5');
      expect(screen.queryByText('Заполнить')).not.toBeInTheDocument();
      unmount();
    }
  });

  it('a form that comes back no longer IN_PROGRESS is read-only (no save/submit)', async () => {
    mockApi({ bound: true, formStatus: 'COMPLETED' });
    await openReport();
    expect(await screen.findByText('На проверке')).toBeInTheDocument();
    expect(screen.getByText(/Редактирование недоступно/)).toBeInTheDocument();
    expect(screen.queryByText('Сохранить черновик')).not.toBeInTheDocument();
    expect(screen.queryByText('Отправить на проверку')).not.toBeInTheDocument();
  });

  it('a save refusal shows the backend message', async () => {
    mockApi({ bound: true });
    apiPost.mockRejectedValue(httpError(409, { message: 'Ответы можно сохранять только для заявки в статусе IN_PROGRESS. Текущий статус: COMPLETED' }));
    await openReport();
    fireEvent.click(await screen.findByText('Сохранить черновик'));
    expect(await screen.findByText(/Ответы можно сохранять только/)).toBeInTheDocument();
  });
});
