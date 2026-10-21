import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { DocumentCaptureFlow } from './DocumentCaptureFlow';

/**
 * KYC-CAPTURE-01: sides in the backend's order, each confirmed before the next; review with view / retake;
 * a retaken side counts again only after its new image is confirmed.
 */

const stopTrack = vi.fn();
const fakeStream = { getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream;
let shots = 0;
const getUserMedia = vi.fn();

beforeEach(() => {
  shots = 0;
  stopTrack.mockReset();
  getUserMedia.mockReset().mockResolvedValue(fakeStream);
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (cb: BlobCallback) {
    shots += 1;
    cb(new Blob([`shot-${shots}`], { type: 'image/jpeg' }));
  });
  let urls = 0;
  URL.createObjectURL = vi.fn(() => `blob:url-${++urls}`);
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function takeAndConfirm(startCamera: boolean) {
  if (startCamera) fireEvent.click(screen.getByText('Включить камеру'));
  fireEvent.click(await screen.findByText('Сделать снимок'));
  fireEvent.click(screen.getByText('Подтвердить'));
}

const text = async (blob: Blob) => blob.text();

describe('DocumentCaptureFlow', () => {
  it('ID card: FRONT -> confirm -> the camera opens by itself for BACK -> confirm -> review -> both reported', async () => {
    const onChange = vi.fn();
    const { container } = render(<DocumentCaptureFlow sides={['FRONT', 'BACK']} onChange={onChange} />);

    expect(screen.getByText('Шаг 1 из 2: Лицевая сторона')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Включить камеру'));
    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: 'environment' }, audio: false });
    fireEvent.click(await screen.findByText('Сделать снимок'));
    expect(screen.getByText('Переснять')).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();                      // a capture is not confirmed automatically
    fireEvent.click(screen.getByText('Подтвердить'));
    expect(onChange).toHaveBeenLastCalledWith(null);               // one side of two: not complete

    expect(await screen.findByText('Шаг 2 из 2: Обратная сторона')).toBeInTheDocument();
    await takeAndConfirm(false);                                   // no click on "Включить камеру" needed
    expect(getUserMedia).toHaveBeenCalledTimes(2);

    expect(screen.getByText('Лицевая сторона ✓')).toBeInTheDocument();
    expect(screen.getByText('Обратная сторона ✓')).toBeInTheDocument();
    const captures = onChange.mock.calls.at(-1)![0];
    expect(Object.keys(captures)).toEqual(['FRONT', 'BACK']);
    expect(await text(captures.FRONT)).toBe('shot-1');
    expect(await text(captures.BACK)).toBe('shot-2');
    expect(container.querySelector('input[type="file"]')).toBeNull();
  });

  it('a retake before confirming discards the shot; only the confirmed one counts', async () => {
    const onChange = vi.fn();
    render(<DocumentCaptureFlow sides={['DATA_PAGE']} onChange={onChange} />);
    fireEvent.click(screen.getByText('Включить камеру'));
    fireEvent.click(await screen.findByText('Сделать снимок'));
    fireEvent.click(screen.getByText('Переснять'));
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByText('Сделать снимок'));
    fireEvent.click(screen.getByText('Подтвердить'));
    expect(await text(onChange.mock.calls.at(-1)![0].DATA_PAGE)).toBe('shot-2');
  });

  it('passport: a single data-page step and no back side anywhere', async () => {
    const onChange = vi.fn();
    render(<DocumentCaptureFlow sides={['DATA_PAGE']} onChange={onChange} />);
    expect(screen.getByText('Шаг 1 из 1: Страница с фотографией и данными')).toBeInTheDocument();
    await takeAndConfirm(true);
    expect(screen.getByText('Страница с фотографией и данными ✓')).toBeInTheDocument();
    expect(screen.queryByText(/Обратная сторона/)).toBeNull();
    expect(Object.keys(onChange.mock.calls.at(-1)![0])).toEqual(['DATA_PAGE']);
  });

  it('review: "Просмотреть" shows the confirmed image; "Переснять" removes it until a new one is confirmed', async () => {
    const onChange = vi.fn();
    render(<DocumentCaptureFlow sides={['FRONT', 'BACK']} onChange={onChange} />);
    await takeAndConfirm(true);
    await screen.findByText('Шаг 2 из 2: Обратная сторона');
    await takeAndConfirm(false);

    fireEvent.click(screen.getAllByText('Просмотреть')[1]);
    expect(screen.getByAltText('Снимок: Обратная сторона')).toBeInTheDocument();

    fireEvent.click(screen.getAllByText('Переснять')[1]);
    expect(onChange).toHaveBeenLastCalledWith(null);              // the old back no longer counts
    expect(URL.revokeObjectURL).toHaveBeenCalled();
    expect(await screen.findByText('Шаг 2 из 2: Обратная сторона')).toBeInTheDocument();
    expect(screen.queryByText('Лицевая сторона ✓')).toBeNull();

    fireEvent.click(await screen.findByText('Сделать снимок'));   // camera restarted by itself
    expect(onChange).toHaveBeenLastCalledWith(null);              // captured, not yet confirmed
    fireEvent.click(screen.getByText('Подтвердить'));
    const captures = onChange.mock.calls.at(-1)![0];
    expect(await text(captures.FRONT)).toBe('shot-1');
    expect(await text(captures.BACK)).toBe('shot-3');
  });

  it('the camera is released after each shot and when the flow is left', async () => {
    const { unmount } = render(<DocumentCaptureFlow sides={['FRONT', 'BACK']} onChange={vi.fn()} />);
    fireEvent.click(screen.getByText('Включить камеру'));
    await screen.findByText('Сделать снимок');
    await act(async () => { unmount(); });
    expect(stopTrack).toHaveBeenCalled();
  });
});
