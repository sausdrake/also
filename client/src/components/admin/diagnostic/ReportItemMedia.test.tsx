import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ReportItemMedia } from './ReportItemMedia';
import api from '../../../api/client';

/** FE-CONTRACT-01: an admin sees and plays the media of a video item through the existing media API. */

vi.mock('../../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

const media = (id: number, extra: Record<string, unknown> = {}) => ({
  id, mediaType: 'VIDEO', mimeType: 'video/mp4', sizeBytes: 1000, sortOrder: id, caption: null, uploadStatus: 'READY',
  processingStatus: 'READY', durationSeconds: 12.4, width: 1280, height: 720, thumbnailAvailable: true, ...extra,
});

function mockGet(list: unknown, url: unknown = { url: 'https://r2.example/signed/v1', expiresAt: '2026-10-21T10:00:00Z' }) {
  apiGet.mockImplementation((path: string) => {
    if (path === '/diagnostic/work/5/items/101/media') return list instanceof Error ? Promise.reject(list) : Promise.resolve({ data: list });
    if (/^\/diagnostic\/media\/\d+\/url$/.test(path)) return url instanceof Error ? Promise.reject(url) : Promise.resolve({ data: url });
    return Promise.reject(new Error(`unexpected ${path}`));
  });
}

beforeEach(() => {
  apiGet.mockReset();
});

describe('FE-CONTRACT-01: report item media for the admin', () => {
  it('lists the files with their status; a READY video plays from a signed URL fetched on demand', async () => {
    mockGet([media(1), media(2, { uploadStatus: 'PENDING_UPLOAD', durationSeconds: null }), media(3, { mediaType: 'PHOTO', durationSeconds: null })]);
    render(<ReportItemMedia requestId={5} itemId={101} />);
    const first = await screen.findByTestId('media-1');
    expect(within(first).getByText('Готово')).toBeInTheDocument();
    expect(within(first).getByText('12 с')).toBeInTheDocument();
    expect(within(screen.getByTestId('media-2')).getByText('Загрузка не завершена')).toBeInTheDocument();
    expect(within(screen.getByTestId('media-2')).queryByRole('button')).not.toBeInTheDocument();
    expect(apiGet).not.toHaveBeenCalledWith('/diagnostic/media/1/url');

    fireEvent.click(within(first).getByRole('button', { name: 'Смотреть' }));
    const video = await within(first).findByLabelText('Видео отчёта');
    expect(video).toHaveAttribute('src', 'https://r2.example/signed/v1');
    expect(within(screen.getByTestId('media-3')).getByRole('button', { name: 'Открыть' })).toBeInTheDocument();
  });

  it('an item without media says so', async () => {
    mockGet([]);
    render(<ReportItemMedia requestId={5} itemId={101} />);
    expect(await screen.findByText('Видео не загружено')).toBeInTheDocument();
  });
});

describe('FE-CONTRACT-01 adversarial', () => {
  it.each([
    ['403', httpError(403, { message: 'Нет доступа к этой заявке' }), 'Нет доступа к этой заявке'],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['malformed', { content: [] }, 'Не удалось загрузить видео пункта'],
  ])('a %s list is an error with a retry, not "no video"', async (_n, answer, text) => {
    let calls = 0;
    apiGet.mockImplementation(() => (calls++ === 0
      ? (answer instanceof Error ? Promise.reject(answer) : Promise.resolve({ data: answer }))
      : Promise.resolve({ data: [media(7)] })));
    render(<ReportItemMedia requestId={5} itemId={101} />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(screen.queryByText('Видео не загружено')).not.toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByTestId('media-7')).toBeInTheDocument();
  });

  it('a refused read URL is explained and nothing is played', async () => {
    mockGet([media(1)], httpError(409, { message: 'Медиафайл недоступен для чтения: FAILED' }));
    render(<ReportItemMedia requestId={5} itemId={101} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Смотреть' }));
    expect(await screen.findByText('Медиафайл недоступен для чтения: FAILED')).toBeInTheDocument();
    expect(screen.queryByLabelText('Видео отчёта')).not.toBeInTheDocument();
  });

  it('a double click asks for one signed URL', async () => {
    apiGet.mockImplementation((path: string) => path.endsWith('/media')
      ? Promise.resolve({ data: [media(1)] }) : new Promise(() => {}));
    render(<ReportItemMedia requestId={5} itemId={101} />);
    const play = await screen.findByRole('button', { name: 'Смотреть' });
    fireEvent.click(play);
    fireEvent.click(play);
    expect(apiGet.mock.calls.filter(c => String(c[0]).endsWith('/url'))).toHaveLength(1);
  });

  it('an unknown upload status shows its raw value and offers no player', async () => {
    mockGet([media(4, { uploadStatus: 'QUARANTINED' })]);
    render(<ReportItemMedia requestId={5} itemId={101} />);
    const row = await screen.findByTestId('media-4');
    expect(within(row).getByText('QUARANTINED')).toBeInTheDocument();
    expect(within(row).queryByRole('button')).not.toBeInTheDocument();
  });
});
