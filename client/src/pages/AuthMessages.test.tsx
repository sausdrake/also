import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { LoginPage } from './LoginPage';
import { RegisterPage } from './RegisterPage';

/**
 * FE-UX-01: after a successful registration the login page says so (RegisterPage used `state.message`,
 * LoginPage reads `state.notice`); a failed login is Russian - the backend sends one masked English
 * 401 for every authentication failure.
 */

vi.mock('axios', () => ({ default: { post: vi.fn(), get: vi.fn() } }));
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

beforeEach(() => {
  axiosPost.mockReset();
});

function renderAuth(start: '/register' | '/login') {
  render(<RouterProvider router={createMemoryRouter([
    { path: '/register', element: <RegisterPage /> },
    { path: '/login', element: <LoginPage /> },
    { path: '/', element: <p>HOME</p> },
  ], { initialEntries: [start] })} />);
}

async function login() {
  fireEvent.change(await screen.findByPlaceholderText('Имя пользователя'), { target: { value: 'ivan' } });
  fireEvent.change(screen.getByPlaceholderText('Пароль'), { target: { value: 'secret12' } });
  fireEvent.click(screen.getByRole('button', { name: /Войти/ }));
}

describe('FE-UX-01: registration success notice', () => {
  it('registering lands on the login page with the success notice', async () => {
    axiosPost.mockResolvedValue({ data: { accessToken: 'a', refreshToken: 'r' } });
    renderAuth('/register');
    fireEvent.change(screen.getByPlaceholderText('Имя пользователя'), { target: { value: 'ivan_petrov' } });
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ivan@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Пароль (мин. 6 символов)'), { target: { value: 'password123' } });
    fireEvent.change(screen.getByPlaceholderText('Подтвердите пароль'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Регистрация успешна! Войдите в систему.');
  });
});

describe('FE-UX-01: login errors are Russian', () => {
  it.each([
    ['masked 401', httpError(401, { error: 'Unauthorized', message: 'Invalid username or password' }), 'Неверное имя пользователя или пароль.'],
    ['429', httpError(429, { message: 'Слишком много попыток входа. Попробуйте позже.' }), 'Слишком много попыток входа. Попробуйте позже.'],
    ['500 English', httpError(500, { message: 'NullPointerException' }), 'Сервер не смог выполнить запрос. Повторите попытку позже.'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером. Проверьте подключение и повторите попытку.'],
    ['400 validation', httpError(400, { error: 'Ошибка валидации', details: { password: 'не должно быть пустым' } }), 'не должно быть пустым'],
  ])('%s', async (_n, error, text) => {
    axiosPost.mockRejectedValue(error);
    renderAuth('/login');
    await login();
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.queryByText(/Invalid|Exception/)).not.toBeInTheDocument();
  });

  it('a response without a token is a Russian failure, not a crash', async () => {
    axiosPost.mockResolvedValue({ data: {} });
    renderAuth('/login');
    await login();
    expect(await screen.findByText('Не удалось войти. Повторите попытку.')).toBeInTheDocument();
  });
});
