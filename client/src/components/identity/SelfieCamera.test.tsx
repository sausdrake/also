import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { SelfieCamera } from './SelfieCamera';

/**
 * MVP-004: the KYC selfie can only come from the live camera - no file input, no fallback to an
 * existing photo when the camera is unavailable.
 */

const stopTrack = vi.fn();
const fakeStream = { getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream;
const capturedJpeg = new Blob(['jpeg-bytes'], { type: 'image/jpeg' });

function setMediaDevices(getUserMedia: unknown) {
  Object.defineProperty(navigator, 'mediaDevices', { value: getUserMedia ? { getUserMedia } : undefined, configurable: true });
}

beforeEach(() => {
  stopTrack.mockReset();
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (cb: BlobCallback) { cb(capturedJpeg); });
  URL.createObjectURL = vi.fn(() => 'blob:selfie-preview');
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('SelfieCamera (MVP-004 live selfie)', () => {
  it('offers no file input - an existing photo cannot be chosen', () => {
    setMediaDevices(vi.fn().mockResolvedValue(fakeStream));
    const { container } = render(<SelfieCamera onCapture={vi.fn()} />);
    expect(container.querySelector('input[type="file"]')).toBeNull();
  });

  it('captures a JPEG from the live camera and releases the camera', async () => {
    const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
    setMediaDevices(getUserMedia);
    const onCapture = vi.fn();
    render(<SelfieCamera onCapture={onCapture} />);

    fireEvent.click(screen.getByText('Включить камеру'));
    await screen.findByText('Сделать снимок');
    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: 'user' }, audio: false });

    fireEvent.click(screen.getByText('Сделать снимок'));

    expect(onCapture).toHaveBeenCalledTimes(1);
    expect(onCapture).toHaveBeenCalledWith(capturedJpeg);
    expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', 0.9);
    expect(stopTrack).toHaveBeenCalled();
    expect(screen.getByAltText('Снятое селфи')).toBeInTheDocument();
  });

  it('retake discards the selfie and turns the camera back on', async () => {
    const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
    setMediaDevices(getUserMedia);
    const onCapture = vi.fn();
    render(<SelfieCamera onCapture={onCapture} />);

    fireEvent.click(screen.getByText('Включить камеру'));
    fireEvent.click(await screen.findByText('Сделать снимок'));
    fireEvent.click(screen.getByText('Переснять'));

    expect(onCapture).toHaveBeenLastCalledWith(null);
    await screen.findByText('Сделать снимок');
    expect(getUserMedia).toHaveBeenCalledTimes(2);
  });

  it('denied camera permission: explains it, captures nothing, offers no upload fallback', async () => {
    setMediaDevices(vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError')));
    const onCapture = vi.fn();
    const { container } = render(<SelfieCamera onCapture={onCapture} />);

    await act(async () => { fireEvent.click(screen.getByText('Включить камеру')); });

    expect(await screen.findByText(/Нет доступа к камере/)).toBeInTheDocument();
    expect(onCapture).not.toHaveBeenCalled();
    expect(container.querySelector('input[type="file"]')).toBeNull();
  });

  it('no camera API at all: explains it, captures nothing', async () => {
    setMediaDevices(undefined);
    const onCapture = vi.fn();
    render(<SelfieCamera onCapture={onCapture} />);

    fireEvent.click(screen.getByText('Включить камеру'));

    await waitFor(() => expect(screen.getByText(/Камера недоступна/)).toBeInTheDocument());
    expect(onCapture).not.toHaveBeenCalled();
  });
});
