import { useEffect, useRef, useState } from 'react';
import type { CaptureSide } from '../../api/identityApi';
import { CAPTURE_SIDE_LABELS } from '../../domain/identity';
import { CameraCapture } from './CameraCapture';

type Confirmed = Partial<Record<CaptureSide, { blob: Blob; url: string }>>;

/**
 * KYC-CAPTURE-01: photographs a document side by side, in the order the backend requires
 * (`sides` from GET /identity/capture-requirements - ID card / driver licence: FRONT then BACK,
 * passport: DATA_PAGE only). Each side is taken with the camera and must be confirmed before the next
 * one starts; then a review lists every side with "Просмотреть" / "Переснять". Retaking a side removes
 * the old image at once - the new one counts only after "Подтвердить".
 *
 * Reports the complete set via onChange(map) once every side is confirmed, otherwise onChange(null).
 * Remount (change `key`) to start over, e.g. for another document type.
 */
export function DocumentCaptureFlow({ sides, onChange, disabled = false }: {
  sides: CaptureSide[];
  onChange: (captures: Partial<Record<CaptureSide, Blob>> | null) => void;
  disabled?: boolean;
}) {
  const [confirmed, setConfirmed] = useState<Confirmed>({});
  const [viewing, setViewing] = useState<CaptureSide | null>(null);
  const [resumed, setResumed] = useState(false);
  const urls = useRef<string[]>([]);

  useEffect(() => () => { urls.current.forEach(u => URL.revokeObjectURL(u)); }, []);

  const current = sides.find(side => !confirmed[side]);
  const stepIndex = current ? sides.indexOf(current) + 1 : sides.length;

  const report = (next: Confirmed) => {
    const complete = sides.every(side => next[side]);
    onChange(complete ? Object.fromEntries(sides.map(side => [side, next[side]!.blob])) : null);
  };

  const confirm = (side: CaptureSide, blob: Blob) => {
    const url = URL.createObjectURL(blob);
    urls.current.push(url);
    const next = { ...confirmed, [side]: { blob, url } };
    setConfirmed(next);
    setResumed(true);
    report(next);
  };

  const retake = (side: CaptureSide) => {
    const old = confirmed[side];
    if (old) URL.revokeObjectURL(old.url);
    const next = { ...confirmed };
    delete next[side];
    setConfirmed(next);
    setViewing(null);
    setResumed(true);
    report(next);
  };

  return (
    <div className="space-y-3" data-testid="document-capture">
      {current ? (
        <div className="space-y-2">
          <p className="text-sm font-medium text-gray-800">
            Шаг {stepIndex} из {sides.length}: {CAPTURE_SIDE_LABELS[current]}
          </p>
          <p className="text-xs text-gray-500">
            Сфотографируйте документ камерой: он должен целиком попасть в кадр, текст — читаться. Загрузить готовый файл нельзя.
          </p>
          <CameraCapture key={current} facingMode="environment" videoLabel={`Камера: ${CAPTURE_SIDE_LABELS[current]}`}
            previewAlt={`Снимок: ${CAPTURE_SIDE_LABELS[current]}`} keepConfirmedPreview={false}
            onConfirm={blob => confirm(current, blob)} disabled={disabled} autoStart={resumed} />
        </div>
      ) : (
        <div className="space-y-2">
          <p className="text-sm font-medium text-gray-800">Проверьте снимки документа</p>
          <ul className="space-y-2">
            {sides.map(side => (
              <li key={side} className="rounded-xl border border-gray-100 p-3 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="flex-1 text-sm text-gray-800">{CAPTURE_SIDE_LABELS[side]} ✓</span>
                  <button type="button" disabled={disabled} onClick={() => setViewing(viewing === side ? null : side)}
                    className="min-h-11 px-3 rounded-xl border text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                    {viewing === side ? 'Скрыть' : 'Просмотреть'}
                  </button>
                  <button type="button" disabled={disabled} onClick={() => retake(side)}
                    className="min-h-11 px-3 rounded-xl border text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50">
                    Переснять
                  </button>
                </div>
                {viewing === side && (
                  <img src={confirmed[side]!.url} alt={`Снимок: ${CAPTURE_SIDE_LABELS[side]}`}
                    className="w-full max-h-[60vh] rounded-xl object-contain bg-gray-50" />
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
