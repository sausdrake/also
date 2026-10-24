import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { LegalDocumentPage } from './LegalDocumentPage';
import { RegisterPage } from './RegisterPage';
import { LegalAcceptanceGate } from '../components/legal/LegalAcceptanceGate';
import { useAuthStore } from '../store/authStore';

/**
 * LEGAL-DATA-01 frontend: only versions in force are shown (never a draft as if valid), the registration asks to
 * tick the exact versions in force and sends their ids, and a signed-in user is asked to accept a new edition.
 */

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('../api/client', () => ({
  default: { get: (...a: unknown[]) => apiGet(...a), post: (...a: unknown[]) => apiPost(...a), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('axios', () => ({ default: { post: vi.fn(), get: vi.fn() } }));
vi.mock('../components/auth/TelegramLoginButton', () => ({ TelegramLoginButton: () => null }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

const terms = { id: 11, documentType: 'TERMS_OF_USE', version: '1.0', title: 'Пользовательское соглашение',
  acceptanceKind: 'ACCEPTANCE', effectiveAt: '2026-10-24T00:00:00Z', contentSha256: 'a'.repeat(64), content: 'Текст соглашения' };
const privacy = { ...terms, id: 12, documentType: 'PRIVACY_POLICY', title: 'Политика конфиденциальности', acceptanceKind: 'ACKNOWLEDGEMENT' };

function at(path: string, element: React.ReactNode, route: string) {
  const router = createMemoryRouter([{ path: route, element }, { path: '/login', element: <p>LOGIN PAGE</p> }],
    { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

beforeEach(() => { apiGet.mockReset(); apiPost.mockReset(); axiosGet.mockReset(); axiosPost.mockReset(); });
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('LEGAL-DATA-01: document page', () => {
  it('nothing in force: says the document is being prepared, shows no text', async () => {
    apiGet.mockRejectedValue({ response: { status: 404 } });
    at('/legal/terms', <LegalDocumentPage />, '/legal/:slug');
    expect(await screen.findByText(/Документ готовится и проходит юридическую проверку/)).toBeInTheDocument();
    expect(apiGet).toHaveBeenCalledWith('/public/legal/documents/TERMS_OF_USE');
  });

  it('in force: shows the version and its text as plain text', async () => {
    apiGet.mockResolvedValue({ data: { ...terms, content: '<b>не разметка</b>' } });
    at('/legal/terms', <LegalDocumentPage />, '/legal/:slug');
    expect(await screen.findByText('<b>не разметка</b>')).toBeInTheDocument();
    expect(screen.getByText(/Редакция 1\.0/)).toBeInTheDocument();
  });
});

describe('LEGAL-DATA-01: registration', () => {
  function fill() {
    fireEvent.change(screen.getByPlaceholderText('Имя пользователя'), { target: { value: 'ivan_petrov' } });
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ivan@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Пароль (мин. 6 символов)'), { target: { value: 'password123' } });
    fireEvent.change(screen.getByPlaceholderText('Подтвердите пароль'), { target: { value: 'password123' } });
  }

  it('documents in force must be ticked; their exact version ids are sent', async () => {
    axiosGet.mockResolvedValue({ data: [terms, privacy, { ...terms, id: 13, documentType: 'KYC_POLICY', acceptanceKind: 'INFORMATIONAL' }] });
    axiosPost.mockResolvedValue({ data: {} });
    at('/register', <RegisterPage />, '/register');
    const boxes = await screen.findAllByRole('checkbox');
    expect(boxes).toHaveLength(2);   // the informational document is not ticked
    fill();
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByText('Отметьте документы платформы, чтобы зарегистрироваться')).toBeInTheDocument();
    expect(axiosPost).not.toHaveBeenCalled();

    boxes.forEach(b => fireEvent.click(b));
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    await waitFor(() => expect(axiosPost).toHaveBeenCalledWith(expect.stringContaining('/api/auth/register'),
      expect.objectContaining({ acceptedDocumentVersionIds: [11, 12] })));
  });

  it('nothing in force: no checkboxes, registration as before', async () => {
    axiosGet.mockResolvedValue({ data: [] });
    axiosPost.mockResolvedValue({ data: {} });
    at('/register', <RegisterPage />, '/register');
    fill();
    fireEvent.click(screen.getByRole('button', { name: 'Зарегистрироваться' }));
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });
});

describe('LEGAL-DATA-01: re-acceptance gate', () => {
  it('asks a signed-in user to accept the editions in force they have not accepted, then closes', async () => {
    useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 5, username: 'u', email: 'u@t', roles: ['ROLE_USER'] } });
    apiGet.mockResolvedValueOnce({ data: [terms] }).mockResolvedValueOnce({ data: [] });
    apiPost.mockResolvedValue({ data: [] });
    at('/', <LegalAcceptanceGate />, '/');
    const box = await screen.findByRole('checkbox');
    const button = screen.getByRole('button', { name: 'Продолжить' });
    expect(button).toBeDisabled();
    fireEvent.click(box);
    fireEvent.click(button);
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/legal/acceptance', { documentVersionIds: [11] }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('nothing to accept, or signed out: no dialog', async () => {
    apiGet.mockResolvedValue({ data: [] });
    at('/', <LegalAcceptanceGate />, '/');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(apiGet).not.toHaveBeenCalled();
  });
});
