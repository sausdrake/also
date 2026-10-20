import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { RegisterPage } from './RegisterPage';

/**
 * BE-AUTH-01 (frontend part): a taken username/email used to come back as 500 "Произошла
 * непредвиденная ошибка". The backend now answers 409 REGISTRATION_CONFLICT with one Russian
 * message for a taken username and a taken email alike (anti-enumeration), which the form shows
 * as is; other failures get a readable message, never English internals.
 */

vi.mock('axios', () => ({ default: { post: vi.fn(), get: vi.fn() } }));
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

function renderForm() {
  const router = createMemoryRouter(
    [{ path: '/register', element: <RegisterPage /> }, { path: '/login', element: <p>LOGIN PAGE</p> }],
    { initialEntries: ['/register'] },
  );
  render(<RouterProvider router={router} />);
  fireEvent.change(screen.getByPlaceholderText('Имя пользователя'), { target: { value: 'ivan_petrov' } });
  fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ivan@example.com' } });
  fireEvent.change(screen.getByPlaceholderText('Пароль (мин. 6 символов)'), { target: { value: 'password123' } });
  fireEvent.change(screen.getByPlaceholderText('Подтвердите пароль'), { target: { value: 'password123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
}

beforeEach(() => {
  axiosPost.mockReset();
});

describe('BE-AUTH-01: registration conflicts are understandable', () => {
  // The backend body is identical for a taken username, a taken email or both.
  const CONFLICT = { status: 409, error: 'Conflict', code: 'REGISTRATION_CONFLICT',
    message: 'Пользователь с указанными данными уже существует.' };

  it.each(['taken username', 'taken email', 'both taken'])('409 (%s) shows the one public message and stays on the form', async () => {
    axiosPost.mockRejectedValue(httpError(409, CONFLICT));
    renderForm();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Пользователь с указанными данными уже существует.');
    expect(alert.textContent).not.toMatch(/логин|email|почт/i);
    expect(screen.queryByText('LOGIN PAGE')).not.toBeInTheDocument();
  });

  it('a 409 without a usable message still gets a Russian explanation', async () => {
    axiosPost.mockRejectedValue(httpError(409, { code: 'REGISTRATION_CONFLICT', message: 'Conflict' }));
    renderForm();
    expect(await screen.findByRole('alert')).toHaveTextContent('Действие сейчас недоступно');
  });

  it('success still goes to the login page', async () => {
    axiosPost.mockResolvedValue({ data: { accessToken: 'a', refreshToken: 'r' } });
    renderForm();
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
  });
});

describe('BE-AUTH-01 adversarial: no English internals or raw markup reach the user', () => {
  it.each([
    ['English 400 from service validation', httpError(400, { error: 'Некорректный запрос', message: 'Username contains invalid characters' }), 'Проверьте введённые данные.'],
    ['400 bean validation details', httpError(400, { error: 'Ошибка валидации', details: { email: 'Некорректный формат email' } }), 'Некорректный формат email'],
    ['500 generic', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['429 limiter', httpError(429, { message: 'Слишком много попыток регистрации. Попробуйте позже.' }), 'Слишком много попыток регистрации'],
    ['HTML gateway page', httpError(502, '<html><body>Bad Gateway</body></html>'), 'Сервер не смог выполнить запрос'],
    ['network down', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером'],
  ])('%s', async (_name, error, expected) => {
    axiosPost.mockImplementation(() => Promise.reject(error));
    renderForm();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(expected);
    expect(alert.textContent).not.toMatch(/Username|Exception|<html/i);
  });
});
