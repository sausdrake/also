import { useCallback, useEffect, useRef, useState } from 'react';
import { CAMERA_ERROR_MESSAGES, cameraErrorKind, cameraUnavailableKind } from './cameraErrors';

type CameraState = 'idle' | 'starting' | 'live' | 'captured' | 'error';

/**
 * MVP-004: the live selfie required for KYC is taken ONLY through the device camera inside the
 * platform (getUserMedia -> canvas -> JPEG). There is deliberately no file input and no
 * fallback to uploading an existing photo: if the camera is unavailable or permission is
 * denied, the user is told so and nothing can be submitted.
 *
 * Reports the captured JPEG via onCapture(blob), and onCapture(null) whenever the selfie is
 * discarded (retake). Remount the component (change its `key`) to reset it after a submission.
 */
export function SelfieCamera({ onCapture, disabled = false }: {
  onCapture: (selfie: Blob | null) => void;
  disabled?: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const previewRef = useRef<string | null>(null);
  const [state, setState] = useState<CameraState>('idle');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const stopCamera = useCallback(() => {
    streamRef.current?.getTracks().forEach(track => track.stop());
    streamRef.current = null;
  }, []);

  const clearPreview = useCallback(() => {
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    previewRef.current = null;
    setPreviewUrl(null);
  }, []);

  useEffect(() => () => {
    stopCamera();
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
  }, [stopCamera]);

  const startCamera = async () => {
    setError(null);
    // CAMERA-01: an insecure page (http:// on a LAN address) hides the camera API - say so, not "no camera"
    const unavailable = cameraUnavailableKind(window);
    if (unavailable) {
      setState('error');
      setError(CAMERA_ERROR_MESSAGES[unavailable]);
      return;
    }
    setState('starting');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' }, audio: false });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play().catch(() => undefined);
      }
      setState('live');
    } catch (e) {
      stopCamera();
      setState('error');
      setError(CAMERA_ERROR_MESSAGES[cameraErrorKind(e)]);
    }
  };

  const capture = () => {
    const video = videoRef.current;
    if (!video) return;
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    const context = canvas.getContext('2d');
    if (!context) {
      setState('error');
      setError('Не удалось сделать снимок. Попробуйте ещё раз.');
      return;
    }
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(blob => {
      if (!blob) {
        setState('error');
        setError('Не удалось сделать снимок. Попробуйте ещё раз.');
        return;
      }
      stopCamera();
      clearPreview();
      const url = URL.createObjectURL(blob);
      previewRef.current = url;
      setPreviewUrl(url);
      setState('captured');
      onCapture(blob);
    }, 'image/jpeg', 0.9);
  };

  const retake = () => {
    clearPreview();
    onCapture(null);
    void startCamera();
  };

  return (
    <div className="space-y-2">
      <p className="text-xs text-gray-500">
        Живое селфи: сделайте снимок камерой прямо сейчас. Загрузить готовое фото нельзя.
      </p>

      {error && <div className="text-xs rounded-lg px-3 py-1.5 bg-red-50 text-red-700">{error}</div>}

      <video ref={videoRef} playsInline muted aria-label="Камера для селфи"
        className={state === 'live' || state === 'starting' ? 'w-full max-w-xs rounded-xl bg-black' : 'hidden'} />

      {state === 'captured' && previewUrl && (
        <img src={previewUrl} alt="Снятое селфи" className="w-full max-w-xs rounded-xl" />
      )}

      <div className="flex gap-2">
        {(state === 'idle' || state === 'error') && (
          <button type="button" onClick={startCamera} disabled={disabled}
            className="px-3 py-1.5 rounded-lg border text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50">
            Включить камеру
          </button>
        )}
        {state === 'live' && (
          <button type="button" onClick={capture} disabled={disabled}
            className="px-3 py-1.5 rounded-lg bg-gray-800 text-white text-sm hover:bg-gray-900 disabled:opacity-50">
            Сделать снимок
          </button>
        )}
        {state === 'captured' && (
          <button type="button" onClick={retake} disabled={disabled}
            className="px-3 py-1.5 rounded-lg border text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50">
            Переснять
          </button>
        )}
      </div>
    </div>
  );
}
