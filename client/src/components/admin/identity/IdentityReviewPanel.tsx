import { useEffect, useState } from 'react';
import { formatDateTime } from '../../../utils/formatDate';
import {
  approve,
  getDocumentUrlAdmin,
  getHistoryAdmin,
  getSelfieUrlAdmin,
  reject,
  revoke,
  type IdentityHistoryEventResponse,
  type IdentityStatusResponse,
  type RevocationReasonCode,
} from '../../../api/identityApi';

const DOCUMENT_TYPE_LABEL: Record<string, string> = {
  NATIONAL_ID: 'Удостоверение личности (ID-карта)',
  INTERNATIONAL_PASSPORT: 'Загранпаспорт',
};

const EVENT_TYPE_LABEL: Record<string, string> = {
  SUBMISSION: 'Подача заявки',
  APPROVAL: 'Подтверждение',
  REJECTION: 'Отклонение',
  REVOCATION: 'Отзыв',
  FAILED_APPROVAL_ATTEMPT: 'Неудачная попытка подтверждения (конфликт)',
};

/** The only 5 revocation reasons the backend accepts - never invent a 6th. reasonText is mandatory only for OTHER. */
const REVOCATION_REASONS: { value: RevocationReasonCode; label: string }[] = [
  { value: 'ADMIN_ERROR', label: 'Ошибка администратора' },
  { value: 'FRAUD_DISCOVERED', label: 'Обнаружено мошенничество' },
  { value: 'DOCUMENT_INVALID', label: 'Документ недействителен' },
  { value: 'IDENTITY_MISMATCH', label: 'Несоответствие личности' },
  { value: 'OTHER', label: 'Другое' },
];

type Action = 'approve' | 'reject' | 'revoke' | null;

interface Props {
  item: IdentityStatusResponse;
  /** Always called after an action attempt settles (success OR error/409) - the caller re-fetches
   * authoritative state (the pending list); this panel never assumes its own action succeeded. */
  onActioned: () => void;
}

/**
 * Phase 57: single-submission admin review/action panel. Approve always goes through the
 * backend's own conflict handling on POST /{id}/approve (AdminIdentityApprovalOrchestrator, can
 * legitimately 409 on a concurrent decision) - this panel never retries blindly, it only ever
 * reports the 409 and defers to onActioned() to bring in fresh state. Revoke is only offered for
 * an already-VERIFIED item (there is no other path to reach it, matching the backend, which only
 * allows revoke() from VERIFIED) - reachable here either via the pending queue never (pending
 * items are never VERIFIED) or via a direct by-ID lookup performed by the parent page.
 */
export function IdentityReviewPanel({ item, onActioned }: Props) {
  const [history, setHistory] = useState<IdentityHistoryEventResponse[]>([]);
  const [docUrl, setDocUrl] = useState<string | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docError, setDocError] = useState<string | null>(null);
  const [selfieLoading, setSelfieLoading] = useState(false);
  const [selfieError, setSelfieError] = useState<string | null>(null);

  const [activeAction, setActiveAction] = useState<Action>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [revokeReasonCode, setRevokeReasonCode] = useState<RevocationReasonCode | ''>('');
  const [revokeReasonText, setRevokeReasonText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  useEffect(() => {
    setActiveAction(null);
    setRejectReason('');
    setRevokeReasonCode('');
    setRevokeReasonText('');
    setMsg(null);
    setDocUrl(null);
    setDocError(null);
    setSelfieError(null);
    getHistoryAdmin(item.id).then(setHistory).catch(() => setHistory([]));
  }, [item.id]);

  const notify = (text: string, ok = true) => { setMsg({ text, ok }); setTimeout(() => setMsg(null), 5000); };

  const viewDocument = () => {
    setDocError(null);
    setDocLoading(true);
    getDocumentUrlAdmin(item.id)
      .then(url => { setDocUrl(url); window.open(url, '_blank', 'noopener'); })
      .catch(() => setDocError('Не удалось получить ссылку на документ'))
      .finally(() => setDocLoading(false));
  };

  // MVP-004: the administrator compares the live selfie with the document before deciding.
  const viewSelfie = () => {
    setSelfieError(null);
    setSelfieLoading(true);
    getSelfieUrlAdmin(item.id)
      .then(url => { window.open(url, '_blank', 'noopener'); })
      .catch(() => setSelfieError('Не удалось получить ссылку на селфи'))
      .finally(() => setSelfieLoading(false));
  };

  const cancel = () => {
    setActiveAction(null);
    setRejectReason('');
    setRevokeReasonCode('');
    setRevokeReasonText('');
  };

  const runApprove = async () => {
    setSubmitting(true);
    try {
      await approve(item.id);
      notify('Личность подтверждена');
    } catch (e: any) {
      notify(e.response?.status === 409
        ? 'Уже обработано другим администратором — статус обновлён'
        : (e.response?.data?.message || 'Ошибка при подтверждении'), false);
    } finally {
      setSubmitting(false);
      cancel();
      onActioned();
    }
  };

  const runReject = async () => {
    if (!rejectReason.trim()) return;
    setSubmitting(true);
    try {
      await reject(item.id, rejectReason.trim());
      notify('Заявка отклонена');
    } catch (e: any) {
      notify(e.response?.status === 409
        ? 'Уже обработано другим администратором — статус обновлён'
        : (e.response?.data?.message || 'Ошибка при отклонении'), false);
    } finally {
      setSubmitting(false);
      cancel();
      onActioned();
    }
  };

  const runRevoke = async () => {
    if (!revokeReasonCode) return;
    if (revokeReasonCode === 'OTHER' && !revokeReasonText.trim()) return;
    setSubmitting(true);
    try {
      await revoke(item.id, revokeReasonCode, revokeReasonText.trim() || null);
      notify('Подтверждение личности отозвано');
    } catch (e: any) {
      notify(e.response?.status === 409
        ? 'Уже обработано другим администратором — статус обновлён'
        : (e.response?.data?.message || 'Ошибка при отзыве'), false);
    } finally {
      setSubmitting(false);
      cancel();
      onActioned();
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-4">
      <div>
        <h3 className="font-semibold text-gray-800 text-sm">Заявка #{item.id} · Пользователь #{item.userId}</h3>
        <p className="text-xs text-gray-500 mt-0.5">{item.status}</p>
      </div>

      {msg && (
        <div className={`text-xs rounded-lg px-3 py-1.5 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
          {msg.text}
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 text-xs text-gray-600">
        <p>Тип документа: {DOCUMENT_TYPE_LABEL[item.documentType] ?? item.documentType}</p>
        <p>Страна выдачи: {item.issuingCountry}</p>
        <p>Номер документа: {item.canonicalDocumentNumber}</p>
        <p>Обновлено: {formatDateTime(item.updatedAt)}</p>
      </div>

      <div>
        <button onClick={viewDocument} disabled={docLoading}
          className="text-xs px-3 py-1.5 rounded-lg border text-gray-600 hover:bg-gray-50 disabled:opacity-50">
          {docLoading ? 'Загрузка...' : 'Просмотреть документ'}
        </button>
        {docError && <p className="text-xs text-red-600 mt-1">{docError}</p>}
        {docUrl && !docError && <p className="text-xs text-gray-400 mt-1">Документ открыт в новой вкладке.</p>}
      </div>

      <div>
        {item.selfieProvided ? (
          <button onClick={viewSelfie} disabled={selfieLoading}
            className="text-xs px-3 py-1.5 rounded-lg border text-gray-600 hover:bg-gray-50 disabled:opacity-50">
            {selfieLoading ? 'Загрузка...' : 'Просмотреть селфи'}
          </button>
        ) : (
          <p className="text-xs rounded-lg px-3 py-1.5 bg-amber-50 text-amber-800">
            В заявке нет живого селфи — она подана до введения обязательного селфи. Подтвердить её нельзя:
            отклоните заявку, чтобы пользователь отправил её повторно с селфи.
          </p>
        )}
        {selfieError && <p className="text-xs text-red-600 mt-1">{selfieError}</p>}
      </div>

      {history.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs text-gray-500">История:</p>
          <div className="space-y-1 max-h-40 overflow-y-auto">
            {history.map(h => (
              <div key={h.id} className="text-xs text-gray-500 border-b border-gray-50 pb-1">
                {EVENT_TYPE_LABEL[h.eventType] ?? h.eventType}
                {h.reviewerAdminId != null && ` · админ #${h.reviewerAdminId}`}
                {h.reasonText && ` · ${h.reasonText}`}
                {' · '}{formatDateTime(h.occurredAt)}
              </div>
            ))}
          </div>
        </div>
      )}

      {item.status === 'PENDING_REVIEW' && (
        <div className="space-y-2 pt-2 border-t border-gray-100">
          {activeAction === null && (
            <div className="flex gap-2">
              {item.selfieProvided && (
                <button onClick={() => setActiveAction('approve')}
                  className="px-4 py-2 rounded-lg bg-green-600 text-white text-sm font-medium hover:bg-green-700">
                  Подтвердить личность
                </button>
              )}
              <button onClick={() => setActiveAction('reject')}
                className="px-4 py-2 rounded-lg bg-red-600 text-white text-sm font-medium hover:bg-red-700">
                Отклонить
              </button>
            </div>
          )}

          {activeAction === 'approve' && (
            <div className="flex gap-2">
              <button onClick={runApprove} disabled={submitting}
                className="px-4 py-2 rounded-lg bg-green-700 text-white text-sm font-medium hover:bg-green-800 disabled:opacity-50">
                {submitting ? '...' : 'Подтвердить одобрение'}
              </button>
              <button onClick={cancel} disabled={submitting}
                className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
                Отмена
              </button>
            </div>
          )}

          {activeAction === 'reject' && (
            <div className="space-y-2">
              <textarea value={rejectReason} onChange={e => setRejectReason(e.target.value)}
                placeholder="Причина отклонения" rows={3}
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              <div className="flex gap-2">
                <button onClick={runReject} disabled={submitting || !rejectReason.trim()}
                  className="px-4 py-2 rounded-lg bg-red-700 text-white text-sm font-medium hover:bg-red-800 disabled:opacity-50">
                  {submitting ? '...' : 'Подтвердить отклонение'}
                </button>
                <button onClick={cancel} disabled={submitting}
                  className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
                  Отмена
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {item.status === 'VERIFIED' && (
        <div className="space-y-2 pt-2 border-t border-gray-100">
          {activeAction === null && (
            <button onClick={() => setActiveAction('revoke')}
              className="px-4 py-2 rounded-lg bg-red-600 text-white text-sm font-medium hover:bg-red-700">
              Отозвать подтверждение
            </button>
          )}

          {activeAction === 'revoke' && (
            <div className="space-y-2">
              <select value={revokeReasonCode} onChange={e => setRevokeReasonCode(e.target.value as RevocationReasonCode | '')}
                className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none">
                <option value="">Выберите причину...</option>
                {REVOCATION_REASONS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
              {revokeReasonCode === 'OTHER' && (
                <textarea value={revokeReasonText} onChange={e => setRevokeReasonText(e.target.value)}
                  placeholder="Уточните причину (обязательно для «Другое»)" rows={3}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              )}
              <div className="flex gap-2">
                <button onClick={runRevoke}
                  disabled={submitting || !revokeReasonCode || (revokeReasonCode === 'OTHER' && !revokeReasonText.trim())}
                  className="px-4 py-2 rounded-lg bg-red-700 text-white text-sm font-medium hover:bg-red-800 disabled:opacity-50">
                  {submitting ? '...' : 'Подтвердить отзыв'}
                </button>
                <button onClick={cancel} disabled={submitting}
                  className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
                  Отмена
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
