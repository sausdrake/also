import type { MediaResponse } from '../../api/diagnosticMediaApi';
import type { UseMediaUploadResult } from '../../hooks/useMediaUpload';
import type { ItemReadiness, MediaListStatus } from '../../hooks/useMediaProcessing';

export interface DiagnosticMediaStatusProps {
  readiness: ItemReadiness;
  mediaList: MediaResponse[];
  videoUpload: UseMediaUploadResult;
  snapshotUpload: UseMediaUploadResult;
  fetchStatus: MediaListStatus;
  onRetryFetch: () => void;
}

const UPLOAD_STATE_LABELS: Partial<Record<UseMediaUploadResult['state'], string>> = {
  creating_intent: 'Подготовка загрузки…',
  uploading: 'Загрузка видео…',
  finalizing: 'Завершение загрузки…',
  upload_failed: 'Не удалось загрузить видео.',
  finalize_failed: 'Не удалось завершить загрузку видео.',
};

/**
 * Compact status display only - no networking logic. Shows server-derived
 * readiness (authoritative for the "is this item satisfied" question)
 * separately from the current in-progress attempt's transient upload state,
 * so a failed later attempt is never confused with the item's real
 * readiness (an earlier READY video is unaffected by it).
 */
export function DiagnosticMediaStatus({
  readiness,
  mediaList,
  videoUpload,
  snapshotUpload,
  fetchStatus,
  onRetryFetch,
}: DiagnosticMediaStatusProps) {
  const videos = mediaList.filter((m) => m.mediaType === 'VIDEO');
  const photoCount = mediaList.filter((m) => m.mediaType === 'PHOTO').length;

  return (
    <div className="mt-2 space-y-1 text-sm">
      {fetchStatus === 'fetch_failed' && (
        <div className="flex items-center gap-2 text-amber-700">
          <span>Не удалось проверить статус видео.</span>
          <button type="button" onClick={onRetryFetch} className="underline">
            Повторить
          </button>
        </div>
      )}

      {readiness === 'ready' && <p className="text-green-700">✓ Обязательное видео готово</p>}
      {readiness === 'missing' && <p className="text-red-600">Обязательное видео отсутствует</p>}
      {readiness === 'unknown' && fetchStatus !== 'fetch_failed' && (
        <p className="text-gray-500">Проверка статуса видео…</p>
      )}

      {videos.length > 0 && (
        <ul className="space-y-0.5 text-gray-600">
          {videos.map((video) => (
            <li key={video.id}>
              {video.uploadStatus === 'READY' && video.processingStatus === 'READY' && '✓ Видео готово'}
              {video.uploadStatus === 'READY' &&
                (video.processingStatus === 'PENDING' || video.processingStatus === 'PROCESSING') &&
                'Обработка видео…'}
              {video.processingStatus === 'FAILED' && 'Обработка не удалась — запишите видео ещё раз'}
              {video.uploadStatus === 'FAILED' && 'Загрузка не удалась'}
            </li>
          ))}
        </ul>
      )}

      {(videoUpload.state === 'creating_intent' ||
        videoUpload.state === 'uploading' ||
        videoUpload.state === 'finalizing') && (
        <p className="text-gray-500">
          {UPLOAD_STATE_LABELS[videoUpload.state]}
          {videoUpload.state === 'uploading' &&
            (videoUpload.progress?.total
              ? ` ${Math.round((videoUpload.progress.loaded / videoUpload.progress.total) * 100)}%`
              : '…')}
        </p>
      )}
      {(videoUpload.state === 'upload_failed' || videoUpload.state === 'finalize_failed') && (
        <p className="text-red-600">{UPLOAD_STATE_LABELS[videoUpload.state]}</p>
      )}

      {(snapshotUpload.state === 'upload_failed' || snapshotUpload.state === 'finalize_failed') && (
        <p className="text-amber-700">Не удалось сохранить снимок.</p>
      )}

      {photoCount > 0 && <p className="text-gray-500">Снимков: {photoCount}</p>}
    </div>
  );
}
