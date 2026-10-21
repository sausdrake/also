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

    expect(await screen.findByText(/Доступ к камере запрещён/)).toBeInTheDocument();
    expect(onCapture).not.toHaveBeenCalled();
    expect(container.querySelector('input[type="file"]')).toBeNull();
  });

  it('no camera API at all: explains it, captures nothing', async () => {
    setMediaDevices(undefined);
    const onCapture = vi.fn();
    render(<SelfieCamera onCapture={onCapture} />);

    fireEvent.click(screen.getByText('Включить камеру'));

    await waitFor(() => expect(screen.getByText(/Этот браузер не даёт доступа к камере/)).toBeInTheDocument());
    expect(onCapture).not.toHaveBeenCalled();
  });

  // CAMERA-01: the reason shown is the real one
  describe('CAMERA-01: the real reason the camera did not start', () => {
    afterEach(() => { Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true }); });

    it('insecure page (http:// on a LAN address): asks for https, never calls the camera, never says "no camera in this browser"', async () => {
      Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
      const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
      setMediaDevices(getUserMedia);
      render(<SelfieCamera onCapture={vi.fn()} />);

      fireEvent.click(screen.getByText('Включить камеру'));

      expect(await screen.findByText(/защищённой странице \(адрес начинается с https:\/\/\)/)).toBeInTheDocument();
      expect(screen.queryByText(/браузер не даёт доступа/)).toBeNull();
      expect(getUserMedia).not.toHaveBeenCalled();
    });

    it.each([
      ['NotAllowedError', /Доступ к камере запрещён/],
      ['NotFoundError', /Камера не найдена/],
      ['OverconstrainedError', /Камера не найдена/],
      ['NotReadableError', /Камера занята другим приложением/],
      ['TypeError', /Не удалось включить камеру/],
    ])('%s -> %s, and the camera can be retried', async (name, text) => {
      setMediaDevices(vi.fn().mockRejectedValue(new DOMException('x', name)));
      render(<SelfieCamera onCapture={vi.fn()} />);

      await act(async () => { fireEvent.click(screen.getByText('Включить камеру')); });

      expect(await screen.findByText(text)).toBeInTheDocument();
      expect(screen.getByText('Включить камеру')).toBeInTheDocument();
    });

    it('a stream that starts after an earlier failure clears the error and goes live', async () => {
      const getUserMedia = vi.fn()
        .mockRejectedValueOnce(new DOMException('busy', 'NotReadableError'))
        .mockResolvedValue(fakeStream);
      setMediaDevices(getUserMedia);
      render(<SelfieCamera onCapture={vi.fn()} />);

      await act(async () => { fireEvent.click(screen.getByText('Включить камеру')); });
      expect(await screen.findByText(/Камера занята/)).toBeInTheDocument();
      await act(async () => { fireEvent.click(screen.getByText('Включить камеру')); });

      expect(await screen.findByText('Сделать снимок')).toBeInTheDocument();
      expect(screen.queryByText(/Камера занята/)).toBeNull();
    });
  });
});
