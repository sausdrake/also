import { useEffect, useState } from 'react';
import { formatDateTime } from '../../utils/formatDate';
import {
  getOwnDocumentUrl,
  getOwnHistory,
  type IdentityHistoryEventResponse,
  type IdentityStatusResponse,
} from '../../api/identityApi';

const STATUS_LABEL: Record<string, string> = {
  PENDING_REVIEW: 'На рассмотрении',
  VERIFIED: 'Подтверждено',
  REJECTED: 'Отклонено',
  REVOKED: 'Отозвано',
};

const STATUS_COLOR: Record<string, string> = {
  PENDING_REVIEW: 'bg-yellow-50 text-yellow-700',
  VERIFIED: 'bg-green-50 text-green-700',
  REJECTED: 'bg-red-50 text-red-700',
  REVOKED: 'bg-red-50 text-red-700',
};

const DOCUMENT_TYPE_LABEL: Record<string, string> = {
  NATIONAL_ID: 'Удостоверение личности (ID-карта)',
  DRIVER_LICENSE: 'Водительское удостоверение',
  INTERNATIONAL_PASSPORT: 'Загранпаспорт',
};

/**
 * Phase 57: pure display of the authoritative status this component is handed - never infers
 * VERIFIED from upload/submission success, always shows exactly the 4 real backend states.
 * For REJECTED/REVOKED, fetches the owner's own history to surface the latest reason text (the
 * status endpoint itself carries no reason field) - reviewer identity is never requested here,
 * matching IdentityHistoryEventResponse.fromDomainForOwner always nulling it server-side anyway.
 */
export function IdentityStatusDisplay({ status }: { status: IdentityStatusResponse }) {
  const [reason, setReason] = useState<string | null>(null);
  const [docUrl, setDocUrl] = useState<string | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docError, setDocError] = useState<string | null>(null);

  useEffect(() => {
    if (status.status !== 'REJECTED' && status.status !== 'REVOKED') {
      setReason(null);
      return;
    }
    let cancelled = false;
    getOwnHistory()
      .then((events: IdentityHistoryEventResponse[]) => {
        if (cancelled) return;
        const last = [...events].reverse().find(e =>
          (status.status === 'REJECTED' && e.eventType === 'REJECTION') ||
          (status.status === 'REVOKED' && e.eventType === 'REVOCATION'));
        setReason(last?.reasonText ?? null);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [status.status, status.updatedAt]);

  const viewDocument = () => {
    setDocError(null);
    setDocLoading(true);
    getOwnDocumentUrl()
      .then(url => { setDocUrl(url); window.open(url, '_blank', 'noopener'); })
      .catch(() => setDocError('Не удалось получить ссылку на документ'))
      .finally(() => setDocLoading(false));
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-gray-800 text-sm">Подтверждение личности</h3>
        <span className={`text-xs font-medium px-2.5 py-1 rounded-full ${STATUS_COLOR[status.status] ?? 'bg-gray-50 text-gray-600'}`}>
          {STATUS_LABEL[status.status] ?? status.status}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-xs text-gray-600">
        <p>Тип документа: {DOCUMENT_TYPE_LABEL[status.documentType] ?? status.documentType}</p>
        <p>Страна выдачи: {status.issuingCountry}</p>
        <p>Номер документа: {status.canonicalDocumentNumber}</p>
        <p>Обновлено: {formatDateTime(status.updatedAt)}</p>
      </div>

      {(status.status === 'REJECTED' || status.status === 'REVOKED') && reason && (
        <div className="text-xs bg-red-50 text-red-700 rounded-lg px-3 py-2">
          Причина: {reason}
        </div>
      )}

      <div>
        <button onClick={viewDocument} disabled={docLoading}
          className="text-xs px-3 py-1.5 rounded-lg border text-gray-600 hover:bg-gray-50 disabled:opacity-50">
          {docLoading ? 'Загрузка...' : 'Просмотреть загруженный документ'}
        </button>
        {docError && <p className="text-xs text-red-600 mt-1">{docError}</p>}
        {docUrl && !docError && <p className="text-xs text-gray-400 mt-1">Документ открыт в новой вкладке.</p>}
      </div>
    </div>
  );
}
