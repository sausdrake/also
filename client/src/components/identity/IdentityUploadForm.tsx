import { useEffect, useState } from 'react';
import {
  CAPTURE_CONTENT_TYPE,
  getCaptureRequirements,
  requestUploadUrl,
  submitIdentity,
  uploadToPresignedUrl,
  type CaptureSide,
  type DocumentType,
} from '../../api/identityApi';
import { apiErrorMessage } from '../../api/errors';
import { DocumentCaptureFlow } from './DocumentCaptureFlow';
import { SelfieCamera } from './SelfieCamera';

const DOCUMENT_TYPES: { value: DocumentType; label: string }[] = [
  { value: 'NATIONAL_ID', label: 'Удостоверение личности (ID-карта)' },
  { value: 'DRIVER_LICENSE', label: 'Водительское удостоверение' },
  { value: 'INTERNATIONAL_PASSPORT', label: 'Загранпаспорт' },
];

type Phase = 'idle' | 'uploading' | 'submitting';
type Requirements = Partial<Record<DocumentType, CaptureSide[]>>;

/**
 * Phase 57: the only supported flow is the existing presigned-URL intent -> upload -> submit
 * (IdentityController: POST /identity/documents/upload-url, then a raw PUT, then POST
 * /identity/submissions) - never a direct multipart upload. issuingCountry is fixed to the
 * single backend-supported value (KG), never a free-text/invented field. Used both for a
 * first-ever submission (no existing row) and for resubmission after REJECTED/REVOKED - the
 * backend (IdentityVerification.resubmit) is the sole authority on which is currently legal;
 * this form never tries to infer that itself.
 *
 * KYC-CAPTURE-01: the document is photographed with the camera, side by side (DocumentCaptureFlow) - there
 * is no file input anywhere in KYC. Which sides a document needs comes from the backend
 * (GET /identity/capture-requirements); every side and the live selfie (MVP-004) get their own capture
 * slot, and the backend re-checks the whole set on submission.
 */
export function IdentityUploadForm({ onSubmitted }: { onSubmitted: () => void }) {
  const [requirements, setRequirements] = useState<Requirements | null | 'error'>(null);
  const [documentType, setDocumentType] = useState<DocumentType | ''>('');
  const [rawDocumentNumber, setRawDocumentNumber] = useState('');
  const [captures, setCaptures] = useState<Partial<Record<CaptureSide, Blob>> | null>(null);
  const [selfie, setSelfie] = useState<Blob | null>(null);
  const [resetKey, setResetKey] = useState(0);
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);

  const loadRequirements = () => {
    getCaptureRequirements()
      .then(list => setRequirements(Object.fromEntries(list.map(r => [r.documentType, r.sides]))))
      .catch(() => setRequirements('error'));
  };
  useEffect(loadRequirements, []);

  const sides = documentType && requirements && requirements !== 'error' ? requirements[documentType] ?? null : null;
  const submitting = phase !== 'idle';
  const canSubmit = !!sides && !!captures && !!selfie && rawDocumentNumber.trim().length > 0 && !submitting;

  const chooseType = (value: DocumentType | '') => {
    setDocumentType(value);
    setCaptures(null);   // the flow below remounts for the new type; nothing taken for the old one counts
  };

  const upload = async (side: CaptureSide, image: Blob, type?: DocumentType) => {
    const slot = await requestUploadUrl(side, type);
    await uploadToPresignedUrl(slot.uploadUrl, image, CAPTURE_CONTENT_TYPE);
    return slot.storageKey;
  };

  const submit = async () => {
    if (!sides || !captures || !selfie || documentType === '') return;
    setError(null);
    try {
      setPhase('uploading');
      const keys: Partial<Record<CaptureSide, string>> = {};
      for (const side of sides) keys[side] = await upload(side, captures[side]!, documentType);
      const selfieKey = await upload('SELFIE', selfie);

      setPhase('submitting');
      await submitIdentity(documentType, rawDocumentNumber.trim(), keys[sides[0]]!, keys.BACK ?? null, selfieKey);

      setCaptures(null);
      setSelfie(null);
      setResetKey(k => k + 1);
      setDocumentType('');
      setRawDocumentNumber('');
      onSubmitted();
    } catch (e) {
      setError(apiErrorMessage(e, 'Не удалось отправить заявку на подтверждение личности'));
      onSubmitted();
    } finally {
      setPhase('idle');
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-4 sm:p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Отправить документ на подтверждение личности</h3>

      {error && <div role="alert" className="text-xs rounded-lg px-3 py-1.5 bg-red-50 text-red-700">{error}</div>}

      <div className="space-y-1.5">
        <p className="text-xs text-gray-500">Страна выдачи документа: Кыргызстан (KG)</p>

        <select value={documentType} onChange={e => chooseType(e.target.value as DocumentType | '')}
          disabled={submitting} aria-label="Тип документа"
          className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50">
          <option value="">Выберите тип документа...</option>
          {DOCUMENT_TYPES.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
        </select>

        <input type="text" value={rawDocumentNumber} onChange={e => setRawDocumentNumber(e.target.value)}
          disabled={submitting} placeholder="Номер документа"
          className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50" />
      </div>

      {requirements === 'error' && (
        <div role="alert" className="text-xs rounded-lg px-3 py-1.5 bg-red-50 text-red-700">
          Не удалось загрузить требования к снимкам документа.{' '}
          <button type="button" className="underline" onClick={() => { setRequirements(null); loadRequirements(); }}>Повторить</button>
        </div>
      )}

      {sides && (
        <DocumentCaptureFlow key={`${documentType}-${resetKey}`} sides={sides} onChange={setCaptures} disabled={submitting} />
      )}

      <SelfieCamera key={resetKey} onCapture={setSelfie} disabled={submitting} />

      <button onClick={submit} disabled={!canSubmit}
        className="min-h-11 w-full sm:w-auto px-4 py-2 rounded-xl bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50">
        {phase === 'uploading' ? 'Загрузка снимков...' : phase === 'submitting' ? 'Отправка...' : 'Отправить на проверку'}
      </button>
    </div>
  );
}
