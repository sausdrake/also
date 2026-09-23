import { useState } from 'react';
import api from '../../../api/client';
import { formatDateTime } from '../../../utils/formatDate';

export interface ReconciliationCaseSummary {
  id: number;
  paymentId: number;
  type: string;
  status: string;
  openedAt: string;
  resolvedByAdminId: number | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  recurredAfterResolution: boolean | null;
}

export interface ReconciliationPaymentSummary {
  id: number;
  amountRequested: number;
  currency: string;
  status: string;
  providerReference: string | null;
  providerTransactionId: string | null;
  amountConfirmed: number | null;
  lateSuccessAfterExpiry: boolean;
  createdAt: string;
  updatedAt: string;
  expiresAt: string | null;
}

export interface ReconciliationEvidenceEntry {
  id: number;
  source: string;
  occurredAt: string;
  rawPayload: string | null;
}

export interface ReconciliationCaseDetailResponse {
  reconciliationCase: ReconciliationCaseSummary;
  payment: ReconciliationPaymentSummary;
  evidence: ReconciliationEvidenceEntry[];
}

const TYPE_LABEL: Record<string, string> = {
  MISMATCH: 'Несовпадение суммы',
  DUPLICATE: 'Дублирующий платёж',
  STUCK: 'Зависший платёж',
  LATE_AFTER_EXPIRY: 'Успех после истечения срока',
};

interface Props {
  detail: ReconciliationCaseDetailResponse;
  /** Always called after a resolve attempt settles (success OR error/409) - the caller re-fetches
   * authoritative state (the case list) and clears the stale selection; this panel never assumes
   * its own action succeeded. */
  onActioned: () => void;
}

/**
 * Phase 62: single-case admin review/resolve panel. Resolution is administrative metadata
 * closure only (backend: AdminReconciliationService#resolveCase never touches Payment/Deposit
 * data - confirmed empirically by the backend's own resolve_IsFinanciallyInert test) - this panel
 * never calculates a balance/discrepancy/corrected amount, only displays values already returned
 * by the backend. Resolve is only offered while status === OPEN; a RESOLVED case is read-only.
 * Unlike identity/settlement, reconciliation resolve has no idempotent-replay semantics - a
 * second resolve attempt on an already-resolved case always 409s, so this panel never retries.
 */
export function ReconciliationCaseDetail({ detail, onActioned }: Props) {
  const [resolutionNote, setResolutionNote] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const { reconciliationCase: c, payment, evidence } = detail;

  const notify = (text: string, ok = true) => { setMsg({ text, ok }); setTimeout(() => setMsg(null), 5000); };

  const runResolve = async () => {
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/reconciliation/cases/${c.id}/resolve`, {
        resolutionNote: resolutionNote.trim() || undefined,
      });
      notify('Дело сверки закрыто');
    } catch (e: any) {
      notify(e.response?.status === 409
        ? 'Дело уже закрыто или его состояние изменилось — список обновлён'
        : e.response?.status === 404
          ? 'Дело не найдено — список обновлён'
          : (e.response?.data?.message || 'Ошибка при закрытии дела'), false);
    } finally {
      setSubmitting(false);
      setResolutionNote('');
      onActioned();
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-4">
      <div>
        <h3 className="font-semibold text-gray-800 text-sm">
          Дело #{c.id} · {TYPE_LABEL[c.type] ?? c.type}
        </h3>
        <p className="text-xs text-gray-500 mt-0.5">{c.status} · открыто {formatDateTime(c.openedAt)}</p>
      </div>

      {msg && (
        <div className={`text-xs rounded-lg px-3 py-1.5 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
          {msg.text}
        </div>
      )}

      <div className="grid grid-cols-2 gap-2 text-xs text-gray-600">
        <p>Платёж: #{payment.id}</p>
        <p>Статус платежа: {payment.status}</p>
        <p>Запрошенная сумма: {payment.amountRequested.toLocaleString('ru')} {payment.currency}</p>
        <p>Подтверждённая сумма: {payment.amountConfirmed != null ? `${payment.amountConfirmed.toLocaleString('ru')} ${payment.currency}` : '—'}</p>
        <p>Референс провайдера: {payment.providerReference ?? '—'}</p>
        <p>ID транзакции провайдера: {payment.providerTransactionId ?? '—'}</p>
      </div>

      {evidence.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs text-gray-500">Наблюдения провайдера:</p>
          <div className="space-y-1 max-h-40 overflow-y-auto">
            {evidence.map(e => (
              <div key={e.id} className="text-xs text-gray-500 border-b border-gray-50 pb-1">
                {e.source} · {formatDateTime(e.occurredAt)}
              </div>
            ))}
          </div>
        </div>
      )}

      {c.status === 'RESOLVED' && (
        <div className="p-3 rounded-xl border border-gray-100 bg-gray-50 space-y-1 text-xs text-gray-600">
          <p>Закрыто администратором #{c.resolvedByAdminId} · {c.resolvedAt && formatDateTime(c.resolvedAt)}</p>
          {c.resolutionNote && <p>Примечание: «{c.resolutionNote}»</p>}
          {c.recurredAfterResolution === true && (
            <p className="text-amber-600">Внимание: наблюдение провайдера поступило уже после закрытия дела.</p>
          )}
        </div>
      )}

      {c.status === 'OPEN' && (
        <div className="space-y-2 pt-2 border-t border-gray-100">
          <p className="text-xs text-gray-400">
            Закрытие дела фиксирует лишь административный факт рассмотрения аномалии. Это не возврат
            средств, не коррекция платежа, не коррекция выплаты и не коррекция расчёта с продавцом —
            любые фактические денежные действия выполняются отдельно, вне этой формы.
          </p>

          <textarea value={resolutionNote} onChange={e => setResolutionNote(e.target.value)}
            placeholder="Примечание к закрытию (необязательно)" rows={2} disabled={submitting}
            className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none" />

          {!confirming ? (
            <button onClick={() => setConfirming(true)} disabled={submitting}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50">
              Закрыть дело
            </button>
          ) : (
            <div className="flex gap-2">
              <button onClick={runResolve} disabled={submitting}
                className="px-4 py-2 rounded-lg bg-blue-700 text-white text-sm font-medium hover:bg-blue-800 disabled:opacity-50">
                {submitting ? '...' : 'Подтвердить закрытие'}
              </button>
              <button onClick={() => setConfirming(false)} disabled={submitting}
                className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
                Отмена
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
