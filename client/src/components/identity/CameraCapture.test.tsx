import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { CameraCapture } from './CameraCapture';

/** KYC-CAPTURE-01: the reusable camera - capture, preview, retake / confirm, release, no file fallback. */

const stopTrack = vi.fn();
const fakeStream = { getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream;
const jpeg = new Blob(['jpeg'], { type: 'image/jpeg' });

function setMediaDevices(getUserMedia: unknown) {
  Object.defineProperty(navigator, 'mediaDevices', { value: getUserMedia ? { getUserMedia } : undefined, configurable: true });
}

beforeEach(() => {
  stopTrack.mockReset();
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (cb: BlobCallback) { cb(jpeg); });
  URL.createObjectURL = vi.fn(() => 'blob:preview');
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
});

const renderCamera = (props: Partial<Parameters<typeof CameraCapture>[0]> = {}) => {
  const onConfirm = vi.fn(), onDiscard = vi.fn();
  const view = render(<CameraCapture facingMode="environment" videoLabel="Камера" previewAlt="Снимок"
    onConfirm={onConfirm} onDiscard={onDiscard} {...props} />);
  return { ...view, onConfirm, onDiscard };
};

const noUploadOffered = (container: HTMLElement) => {
  expect(container.querySelector('input[type="file"]')).toBeNull();
  expect(container.textContent).not.toMatch(/галере|выбрать файл|choose file|upload/i);
};

describe('CameraCapture', () => {
  it('capture -> preview with "Переснять" / "Подтвердить"; only "Подтвердить" reports the JPEG', async () => {
    const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
    setMediaDevices(getUserMedia);
    const { container, onConfirm } = renderCamera();

    fireEvent.click(screen.getByText('Включить камеру'));
    fireEvent.click(await screen.findByText('Сделать снимок'));
    expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', 0.9);
    expect(stopTrack).toHaveBeenCalled();                          // camera released once the shot is taken
    expect(screen.getByAltText('Снимок')).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('Подтвердить'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith(jpeg);
    noUploadOffered(container);
  });

  it('"Переснять" discards the shot and turns the camera on again', async () => {
    const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
    setMediaDevices(getUserMedia);
    const { onConfirm, onDiscard } = renderCamera();
    fireEvent.click(screen.getByText('Включить камеру'));
    fireEvent.click(await screen.findByText('Сделать снимок'));
    fireEvent.click(screen.getByText('Переснять'));
    expect(onDiscard).toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
    await screen.findByText('Сделать снимок');
    expect(getUserMedia).toHaveBeenCalledTimes(2);
  });

  it('autoStart turns the camera on without a click', async () => {
    const getUserMedia = vi.fn().mockResolvedValue(fakeStream);
    setMediaDevices(getUserMedia);
    renderCamera({ autoStart: true });
    expect(await screen.findByText('Сделать снимок')).toBeInTheDocument();
  });

  it('leaving the page (pagehide / hidden tab) and unmounting release the camera', async () => {
    setMediaDevices(vi.fn().mockResolvedValue(fakeStream));
    const { unmount } = renderCamera();
    fireEvent.click(screen.getByText('Включить камеру'));
    await screen.findByText('Сделать снимок');
    act(() => { window.dispatchEvent(new Event('pagehide')); });
    expect(stopTrack).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Включить камеру')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Включить камеру'));
    await screen.findByText('Сделать снимок');
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(stopTrack).toHaveBeenCalledTimes(2);
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });

    fireEvent.click(screen.getByText('Включить камеру'));
    await screen.findByText('Сделать снимок');
    unmount();
    expect(stopTrack).toHaveBeenCalledTimes(3);
  });

  it.each([
    ['NotAllowedError', /Разрешите доступ к камере в настройках браузера/],
    ['NotFoundError', /Камера не найдена/],
    ['NotReadableError', /Камера занята/],
    ['AbortError', /Камера занята/],
    ['TypeError', /Не удалось включить камеру/],
  ])('%s -> its own message, nothing captured, no upload fallback', async (name, message) => {
    setMediaDevices(vi.fn().mockRejectedValue(new DOMException('x', name)));
    const { container, onConfirm } = renderCamera();
    await act(async () => { fireEvent.click(screen.getByText('Включить камеру')); });
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(onConfirm).not.toHaveBeenCalled();
    noUploadOffered(container);
  });

  it('no camera API / insecure page -> the real reason, the camera is never asked', async () => {
    setMediaDevices(undefined);
    const first = renderCamera();
    fireEvent.click(screen.getByText('Включить камеру'));
    expect(await screen.findByRole('alert')).toHaveTextContent(/браузер не даёт доступа к камере/);
    noUploadOffered(first.container);
    first.unmount();

    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
    const getUserMedia = vi.fn();
    setMediaDevices(getUserMedia);
    const second = renderCamera();
    fireEvent.click(screen.getByText('Включить камеру'));
    expect(await screen.findByRole('alert')).toHaveTextContent(/https:\/\//);
    expect(getUserMedia).not.toHaveBeenCalled();
    noUploadOffered(second.container);
  });
});
