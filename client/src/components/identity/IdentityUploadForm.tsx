import { useState } from 'react';
import {
  requestUploadUrl,
  submitIdentity,
  uploadToPresignedUrl,
  type DocumentType,
} from '../../api/identityApi';

const DOCUMENT_TYPES: { value: DocumentType; label: string }[] = [
  { value: 'NATIONAL_ID', label: 'Удостоверение личности (ID-карта)' },
  { value: 'INTERNATIONAL_PASSPORT', label: 'Загранпаспорт' },
];

type Phase = 'idle' | 'uploading' | 'submitting';

/**
 * Phase 57: the only supported flow is the existing presigned-URL intent -> upload -> submit
 * (IdentityController: POST /identity/documents/upload-url, then a raw PUT, then POST
 * /identity/submissions) - never a direct multipart upload. issuingCountry is fixed to the
 * single backend-supported value (KG), never a free-text/invented field. Used both for a
 * first-ever submission (no existing row) and for resubmission after REJECTED/REVOKED - the
 * backend (IdentityVerification.resubmit) is the sole authority on which is currently legal;
 * this form never tries to infer that itself.
 */
export function IdentityUploadForm({ onSubmitted }: { onSubmitted: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [documentType, setDocumentType] = useState<DocumentType | ''>('');
  const [rawDocumentNumber, setRawDocumentNumber] = useState('');
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);

  const submitting = phase !== 'idle';
  const canSubmit = !!file && documentType !== '' && rawDocumentNumber.trim().length > 0 && !submitting;

  const submit = async () => {
    if (!file || documentType === '') return;
    setError(null);
    try {
      setPhase('uploading');
      const slot = await requestUploadUrl(file.type || 'application/octet-stream');
      await uploadToPresignedUrl(slot.uploadUrl, file, file.type || 'application/octet-stream');

      setPhase('submitting');
      await submitIdentity(documentType, rawDocumentNumber.trim(), slot.storageKey);

      setFile(null);
      setDocumentType('');
      setRawDocumentNumber('');
      onSubmitted();
    } catch (e: any) {
      if (e.response?.status === 409) {
        setError(e.response?.data?.message || 'Заявка уже существует в этом статусе — статус обновлён');
      } else {
        setError(e.response?.data?.message || 'Не удалось отправить заявку на подтверждение личности');
      }
      onSubmitted();
    } finally {
      setPhase('idle');
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Отправить документ на подтверждение личности</h3>

      {error && (
        <div className="text-xs rounded-lg px-3 py-1.5 bg-red-50 text-red-700">{error}</div>
      )}

      <div className="space-y-1.5">
        <p className="text-xs text-gray-500">Страна выдачи документа: Кыргызстан (KG)</p>

        <select value={documentType} onChange={e => setDocumentType(e.target.value as DocumentType | '')}
          disabled={submitting}
          className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50">
          <option value="">Выберите тип документа...</option>
          {DOCUMENT_TYPES.map(d => <option key={d.value} value={d.value}>{d.label}</option>)}
        </select>

        <input type="text" value={rawDocumentNumber} onChange={e => setRawDocumentNumber(e.target.value)}
          disabled={submitting} placeholder="Номер документа"
          className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:opacity-50" />

        <input type="file" accept="image/jpeg,image/png,application/pdf"
          onChange={e => setFile(e.target.files?.[0] ?? null)} disabled={submitting}
          className="w-full text-sm text-gray-600 disabled:opacity-50" />
      </div>

      <button onClick={submit} disabled={!canSubmit}
        className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50">
        {phase === 'uploading' ? 'Загрузка файла...' : phase === 'submitting' ? 'Отправка...' : 'Отправить на проверку'}
      </button>
    </div>
  );
}
