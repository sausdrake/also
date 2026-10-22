import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/**
 * FE-KYC-01: POST /diagnostic/requests and POST /withdrawals are behind VerifiedIdentityGate. A KYC
 * refusal is guidance with a link to the identity tab; any other error is red (the request tab used
 * to show errors in the same blue box as "Заявка создана").
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });
const KYC = httpError(403, { status: 403, error: 'Forbidden', code: 'IDENTITY_VERIFICATION_REQUIRED',
  message: 'Операция доступна только после подтверждения личности (KYC). Пройдите верификацию личности.' });

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/deposits/my') return Promise.resolve({ data: { balance: 1000, blockedAmount: 0, availableBalance: 1000 } });
    if (url.startsWith('/diagnostic/requests/my')) return Promise.resolve({ data: { content: [] } });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset();
});

function renderProfile(path: string) {
  const router = createMemoryRouter([
    { path: '/profile/*', element: <ProfilePage /> },
  ], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

async function submitRequest() {
  fireEvent.click(await screen.findByText('+ Подать заявку'));
  fireEvent.change(screen.getByPlaceholderText('Марка (BMW, Toyota...)'), { target: { value: 'BMW' } });
  fireEvent.change(screen.getByPlaceholderText('Модель (X5, Camry...)'), { target: { value: 'X5' } });
  fireEvent.change(screen.getByPlaceholderText('Год выпуска (напр. 2020)'), { target: { value: '2020' } });
  fireEvent.change(screen.getByPlaceholderText('VIN — 17 символов'), { target: { value: 'WBA00000000000001' } });
  fireEvent.click(screen.getByRole('button', { name: 'Создать' }));
}

async function submitWithdrawal() {
  fireEvent.change(await screen.findByPlaceholderText('Сумма'), { target: { value: '100' } });
  fireEvent.click(screen.getByRole('button', { name: 'Вывести' }));
}

describe('FE-KYC-01: diagnostic request', () => {
  it('a KYC refusal shows guidance with the identity link', async () => {
    apiPost.mockRejectedValue(KYC);
    renderProfile('/profile/requests');
    await submitRequest();
    expect(await screen.findByText('Чтобы подать заявку на диагностику, подтвердите личность.')).toBeInTheDocument();
    expect(screen.queryByText(/доверенного/)).toBeNull();
    expect(screen.getByRole('link', { name: 'Пройти подтверждение личности' })).toHaveAttribute('href', '/profile/identity');
  });

  it('another error is red (alert), not the blue success box, and has no identity link', async () => {
    apiPost.mockRejectedValue(httpError(400, { error: 'Ошибка валидации', details: { vin: 'VIN должен содержать 17 символов' } }));
    renderProfile('/profile/requests');
    await submitRequest();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('VIN должен содержать 17 символов');
    expect(alert.className).toContain('bg-red-50');
    expect(screen.queryByRole('link', { name: 'Пройти подтверждение личности' })).not.toBeInTheDocument();
  });

  it('success stays the blue status box', async () => {
    apiPost.mockResolvedValue({ data: {} });
    renderProfile('/profile/requests');
    await submitRequest();
    expect(await screen.findByRole('status')).toHaveTextContent('Заявка создана');
  });
});

describe('FE-KYC-01: withdrawal', () => {
  it('a KYC refusal shows guidance with the identity link', async () => {
    apiPost.mockRejectedValue(KYC);
    renderProfile('/profile');
    await submitWithdrawal();
    expect(await screen.findByText('Чтобы вывести средства, подтвердите личность.')).toBeInTheDocument();
    // UX-KYC-01: a withdrawal needs KYC only - no word about the trusted status
    expect(screen.queryByText(/доверенного/)).toBeNull();
    expect(screen.getByRole('link', { name: 'Пройти подтверждение личности' })).toHaveAttribute('href', '/profile/identity');
  });

  it.each([
    ['409 Russian', httpError(409, { message: 'Недостаточно доступных средств для вывода' }), 'Недостаточно доступных средств для вывода'],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером'],
    ['English 403 without code', httpError(403, { error: 'Forbidden', message: 'Access is denied' }), 'Недостаточно прав для этого действия.'],
  ])('%s -> its own message, no identity link', async (_n, error, text) => {
    apiPost.mockRejectedValue(error);
    renderProfile('/profile');
    await submitWithdrawal();
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Пройти подтверждение личности' })).not.toBeInTheDocument();
  });
});
