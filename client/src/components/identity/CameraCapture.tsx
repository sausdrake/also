import { useCallback, useEffect, useRef, useState } from 'react';
import { CAMERA_ERROR_MESSAGES, cameraErrorKind, cameraUnavailableKind } from './cameraErrors';

type CameraState = 'idle' | 'starting' | 'live' | 'captured' | 'confirmed' | 'error';

/**
 * KYC-CAPTURE-01: the one camera component for KYC (document sides and the live selfie). The image can only
 * come from the device camera (getUserMedia -> canvas -> JPEG): there is no file input and no gallery
 * fallback - when the camera cannot start, the real reason is shown (cameraErrors) and nothing can be taken.
 *
 * Flow: live preview -> "Сделать снимок" -> captured preview -> "Переснять" | "Подтвердить". Only a
 * confirmed image is reported (onConfirm); "Переснять" discards it (onDiscard) and turns the camera on again.
 * The camera tracks are released after every capture, on unmount and when the page is hidden/left.
 */
export function CameraCapture({
  facingMode,
  videoLabel,
  previewAlt,
  onConfirm,
  onDiscard,
  disabled = false,
  autoStart = false,
  keepConfirmedPreview = true,
}: {
  facingMode: 'user' | 'environment';
  videoLabel: string;
  previewAlt: string;
  onConfirm: (image: Blob) => void;
  onDiscard?: () => void;
  disabled?: boolean;
  /** Turn the camera on immediately (e.g. the next document side after a confirmed one). */
  autoStart?: boolean;
  /** After "Подтвердить" keep showing the image with "Переснять" (selfie); false when the parent moves on. */
  keepConfirmedPreview?: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const previewRef = useRef<string | null>(null);
  const blobRef = useRef<Blob | null>(null);
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
    blobRef.current = null;
    setPreviewUrl(null);
  }, []);

  const startCamera = useCallback(async () => {
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
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode }, audio: false });
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
  }, [facingMode, stopCamera]);

  useEffect(() => {
    // leaving the page (tab switch, navigation, app backgrounded) must not keep the camera on
    const release = () => {
      if (!streamRef.current) return;
      stopCamera();
      setState(s => (s === 'live' || s === 'starting' ? 'idle' : s));
    };
    const onVisibility = () => { if (document.visibilityState === 'hidden') release(); };
    window.addEventListener('pagehide', release);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', release);
      document.removeEventListener('visibilitychange', onVisibility);
      stopCamera();
      if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    };
  }, [stopCamera]);

  useEffect(() => {
    if (autoStart && !disabled) {
      const timer = setTimeout(() => { void startCamera(); }, 0);
      return () => clearTimeout(timer);
    }
    return undefined;
    // only on mount: a later prop change never restarts a camera the user is using
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      blobRef.current = blob;
      setPreviewUrl(url);
      setState('captured');
    }, 'image/jpeg', 0.9);
  };

  const confirm = () => {
    const blob = blobRef.current;
    if (!blob) return;
    setState('confirmed');
    onConfirm(blob);
  };

  const retake = () => {
    clearPreview();
    onDiscard?.();
    void startCamera();
  };

  const button = 'min-h-11 px-4 py-2 rounded-xl text-sm font-medium disabled:opacity-50';
  const showPreview = previewUrl && (state === 'captured' || (state === 'confirmed' && keepConfirmedPreview));

  return (
    <div className="space-y-2">
      {error && <div role="alert" className="text-xs rounded-lg px-3 py-1.5 bg-red-50 text-red-700">{error}</div>}

      <video ref={videoRef} playsInline muted aria-label={videoLabel}
        className={state === 'live' || state === 'starting' ? 'w-full max-h-[60vh] rounded-xl bg-black object-cover' : 'hidden'} />

      {showPreview && <img src={previewUrl} alt={previewAlt} className="w-full max-h-[60vh] rounded-xl object-contain bg-gray-50" />}

      <div className="flex flex-wrap gap-2 pb-[env(safe-area-inset-bottom)]">
        {(state === 'idle' || state === 'error') && (
          <button type="button" onClick={startCamera} disabled={disabled}
            className={`${button} border text-gray-700 hover:bg-gray-50`}>
            Включить камеру
          </button>
        )}
        {state === 'live' && (
          <button type="button" onClick={capture} disabled={disabled}
            className={`${button} flex-1 bg-gray-800 text-white hover:bg-gray-900`}>
            Сделать снимок
          </button>
        )}
        {state === 'captured' && (
          <>
            <button type="button" onClick={retake} disabled={disabled}
              className={`${button} flex-1 border text-gray-700 hover:bg-gray-50`}>
              Переснять
            </button>
            <button type="button" onClick={confirm} disabled={disabled}
              className={`${button} flex-1 bg-blue-600 text-white hover:bg-blue-700`}>
              Подтвердить
            </button>
          </>
        )}
        {state === 'confirmed' && keepConfirmedPreview && (
          <button type="button" onClick={retake} disabled={disabled}
            className={`${button} border text-gray-700 hover:bg-gray-50`}>
            Переснять
          </button>
        )}
      </div>
    </div>
  );
}
