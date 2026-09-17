import { useEffect, useMemo, useRef } from 'react';
import type { UseCameraStreamResult } from '../../hooks/useCameraStream';
import type { UseVideoRecorderResult } from '../../hooks/useVideoRecorder';
import { selectSupportedMimeType } from '../../hooks/useVideoRecorder';

const SNAPSHOT_JPEG_QUALITY = 0.85;

export interface DiagnosticVideoRecorderProps {
  camera: UseCameraStreamResult;
  recorder: UseVideoRecorderResult;
  onSnapshot: (blob: Blob) => void;
}

const CAMERA_ERROR_MESSAGES: Partial<Record<UseCameraStreamResult['state'], string>> = {
  permission_denied: 'Доступ к камере запрещён. Разрешите доступ в настройках браузера.',
  unavailable: 'Камера не найдена на этом устройстве.',
  error: 'Не удалось получить доступ к камере.',
};

/**
 * Presentational + light glue only: camera preview, record/stop/snapshot
 * controls, timer. No API orchestration, no upload logic - composes the
 * camera and recorder hooks it's given and calls onSnapshot with a raw Blob.
 */
export function DiagnosticVideoRecorder({ camera, recorder, onSnapshot }: DiagnosticVideoRecorderProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  // A pure, cheap, deterministic check (no browser state that changes after
  // mount) - memoized via useMemo rather than a ref, since reading a ref's
  // value during render is unsafe (react-hooks/refs).
  const mimeSupported = useMemo(
    () => typeof MediaRecorder !== 'undefined' && selectSupportedMimeType() != null,
    [],
  );

  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.srcObject = camera.stream;
    }
  }, [camera.stream]);

  if (!mimeSupported) {
    return (
      <div className="rounded bg-yellow-50 p-3 text-sm text-yellow-800" role="alert">
        Запись видео не поддерживается этим браузером.
      </div>
    );
  }

  const handleSnapshot = () => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas || !video.videoWidth || !video.videoHeight) return;
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(
      (blob) => {
        if (blob) onSnapshot(blob);
      },
      'image/jpeg',
      SNAPSHOT_JPEG_QUALITY,
    );
  };

  const minutes = Math.floor(recorder.elapsedSeconds / 60);
  const seconds = recorder.elapsedSeconds % 60;
  const timerLabel = `${minutes}:${seconds.toString().padStart(2, '0')}`;

  const cameraError = CAMERA_ERROR_MESSAGES[camera.state];

  return (
    <div>
      {cameraError && (
        <div className="rounded bg-red-50 p-3 text-sm text-red-700" role="alert">
          {cameraError}
        </div>
      )}

      {camera.state === 'idle' && (
        <button
          type="button"
          onClick={() => {
            void camera.start();
          }}
          className="min-h-[44px] rounded bg-blue-600 px-4 py-2 text-sm text-white"
        >
          Включить камеру
        </button>
      )}

      {camera.state === 'requesting' && (
        <p className="text-sm text-gray-500">Запрашиваем доступ к камере…</p>
      )}

      {camera.state === 'ready' && (
        <div className="relative">
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className="w-full rounded bg-black object-cover"
            style={{ aspectRatio: '16 / 9' }}
            aria-label="Предпросмотр камеры"
          />
          <canvas ref={canvasRef} className="hidden" aria-hidden="true" />

          {recorder.state === 'recording' && (
            <div
              className="absolute left-2 top-2 flex items-center gap-2 rounded bg-black/60 px-2 py-1 text-xs text-white"
              role="status"
            >
              <span className="h-2 w-2 animate-pulse rounded-full bg-red-500" aria-hidden="true" />
              <span>{timerLabel}</span>
            </div>
          )}

          <div className="mt-2 flex gap-2">
            {recorder.state !== 'recording' ? (
              <button
                type="button"
                onClick={() => recorder.start(camera.stream!)}
                disabled={recorder.state === 'finalizing_blob' || !camera.stream}
                className="min-h-[44px] flex-1 rounded bg-red-600 px-4 py-2 text-sm text-white disabled:opacity-50"
              >
                Начать запись
              </button>
            ) : (
              <button
                type="button"
                onClick={recorder.stop}
                className="min-h-[44px] flex-1 rounded bg-gray-700 px-4 py-2 text-sm text-white"
              >
                Остановить
              </button>
            )}
            <button
              type="button"
              onClick={handleSnapshot}
              className="min-h-[44px] min-w-[44px] rounded bg-gray-200 px-4 py-2 text-sm"
              aria-label="Сделать снимок"
              title="Сделать снимок"
            >
              📷
            </button>
          </div>

          {recorder.state === 'failed' && (
            <p className="mt-1 text-sm text-red-600">Запись не удалась, попробуйте ещё раз.</p>
          )}
        </div>
      )}
    </div>
  );
}
