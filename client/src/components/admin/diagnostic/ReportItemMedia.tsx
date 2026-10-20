import { useEffect, useState } from 'react';
import { getReadUrl, listMedia, type MediaResponse, type UploadStatus } from '../../../api/diagnosticMediaApi';
import { apiErrorMessage } from '../../../api/errors';

/**
 * FE-CONTRACT-01: the admin reviews a report without seeing its videos - the report DTO carries the
 * answers (photos as photoUrls) but not the media of video items. The backend already lets an admin
 * list an item's media (GET /diagnostic/work/{requestId}/items/{itemId}/media) and get a short-lived
 * signed read URL (GET /diagnostic/media/{id}/url, uploadStatus READY only), so no DTO change is
 * needed. A link is fetched only when the admin asks to play a file (signed URLs expire).
 */

const UPLOAD_LABEL: Record<UploadStatus, string> = {
  PENDING_UPLOAD: 'Загрузка не завершена',
  READY: 'Готово',
  FAILED: 'Ошибка загрузки',
  EXPIRED: 'Загрузка не завершена (срок истёк)',
  DELETED: 'Удалено',
};

type Load = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ok'; items: MediaResponse[] };

function MediaFile({ media }: { media: MediaResponse }) {
  const [url, setUrl] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState('');
  const isVideo = media.mediaType === 'VIDEO';
  const ready = media.uploadStatus === 'READY';

  const open = async () => {
    if (opening) return;
    setOpening(true);
    setError('');
    try {
      setUrl((await getReadUrl(media.id)).url);
    } catch (e) {
      setError(apiErrorMessage(e, 'Не удалось получить ссылку на файл'));
    } finally {
      setOpening(false);
    }
  };

  return (
    <li className="rounded-lg border border-gray-100 bg-white p-2 text-xs space-y-1.5" data-testid={`media-${media.id}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium text-gray-700">{isVideo ? '🎥 Видео' : '📷 Фото'}</span>
        {media.durationSeconds != null && <span className="text-gray-400">{Math.round(media.durationSeconds)} с</span>}
        <span className={ready ? 'text-green-700' : 'text-amber-700'}>
          {UPLOAD_LABEL[media.uploadStatus] ?? media.uploadStatus}
        </span>
        {ready && !url && (
          <button type="button" onClick={open} disabled={opening}
            className="ml-auto rounded-md bg-blue-600 px-2 py-1 font-medium text-white disabled:opacity-50">
            {opening ? 'Открываем…' : isVideo ? 'Смотреть' : 'Открыть'}
          </button>
        )}
      </div>
      {error && <p role="alert" className="text-red-600">{error}</p>}
      {url && (isVideo
        ? <video src={url} controls playsInline preload="metadata" className="w-full max-h-72 rounded bg-black" aria-label="Видео отчёта" />
        : <img src={url} alt="Фото отчёта" className="max-h-72 rounded border" />)}
    </li>
  );
}

export function ReportItemMedia({ requestId, itemId }: { requestId: number; itemId: number }) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    listMedia(requestId, itemId)
      .then(data => {
        if (cancelled) return;
        if (!Array.isArray(data)) throw new Error('Malformed media list');
        setLoad({ kind: 'ok', items: data.filter(m => m && typeof m.id === 'number') });
      })
      .catch(e => { if (!cancelled) setLoad({ kind: 'error', message: apiErrorMessage(e, 'Не удалось загрузить видео пункта') }); });
    return () => { cancelled = true; };
  }, [requestId, itemId, attempt]);

  const retry = () => { setLoad({ kind: 'loading' }); setAttempt(a => a + 1); };

  if (load.kind === 'loading') return <p className="text-xs text-gray-400">Загрузка видео…</p>;
  if (load.kind === 'error') return (
    <div role="alert" className="text-xs text-red-700 space-x-2">
      <span>{load.message}</span>
      <button type="button" onClick={retry} className="underline">Повторить</button>
    </div>
  );
  if (load.items.length === 0) return <p className="text-xs text-amber-700">Видео не загружено</p>;
  return (
    <ul className="space-y-1.5" aria-label="Медиафайлы пункта">
      {load.items.map(m => <MediaFile key={m.id} media={m} />)}
    </ul>
  );
}
