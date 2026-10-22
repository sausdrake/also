import { useEffect, useState } from 'react';
import api from '../../../api/client';
import { violationLabel } from '../../../domain/violation';
import { formatDateTime } from '../../../utils/formatDate';

interface ViolationDefinition {
  id: number;
  code: string;
  description: string;
}

export interface ViolationResolution {
  sellerDiagnosticCommitmentId: number;
  violationDefinitionIds: number[];
  rawPenaltyAmount: number;
  appliedPenaltyAmount: number;
  refundAmount: number;
  resolvedAt: string;
}

interface Props {
  requestId: number;
  commitmentStatus: string | null | undefined;
  violationResolution: ViolationResolution | null | undefined;
  onDone: () => void;
}

/**
 * Seller-violation-resolution UI (X3-E companion, Phase 55 target): POST
 * /admin/diagnostic/reports/{requestId}/seller-violation-resolution - the admin selects only
 * violationDefinitionIds from the existing policy catalogue (GET
 * /admin/policy/violation-definitions, the same catalogue SaleFailurePanel already uses).
 * Penalty/cap/refund are entirely computed server-side by SellerViolationResolutionEngine -
 * never calculated here. Structurally mirrors SaleFailurePanel.tsx's already-proven pattern:
 * closed-set checklist, confirm-before-submit, disabled-during-submit, 409-&gt;refetch
 * authoritative state (never an optimistic update).
 *
 * State contract, driven entirely by the already-existing read model
 * (DiagnosticRequestResponse.commitmentStatus/violationResolution):
 * - violationResolution present -&gt; read-only summary of the existing resolution.
 * - commitmentStatus !== AWAITING_FINALIZATION (including null/FROZEN) -&gt; not eligible, render
 *   nothing. The caller additionally gates on rejectionCategory === 'SELLER_VIOLATION' before
 *   ever rendering this component at all.
 * - otherwise -&gt; the resolution form.
 */
export function SellerViolationResolutionPanel({ requestId, commitmentStatus, violationResolution, onDone }: Props) {
  const [violations, setViolations] = useState<ViolationDefinition[]>([]);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const eligible = commitmentStatus === 'AWAITING_FINALIZATION';

  useEffect(() => {
    if (eligible && !violationResolution && violations.length === 0) {
      api.get<ViolationDefinition[]>('/admin/policy/violation-definitions')
        .then(r => setViolations(r.data))
        .catch(() => {});
    }
  }, [eligible, violationResolution, violations.length]);

  const notify = (text: string, ok = true) => { setMsg({ text, ok }); setTimeout(() => setMsg(null), 4000); };

  if (violationResolution) {
    return (
      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-2">
        <h3 className="font-semibold text-gray-800 text-sm">Финансовое разрешение нарушения продавца</h3>
        <p className="text-xs text-gray-500">
          Применённые нарушения (ID): {violationResolution.violationDefinitionIds.join(', ') || '—'}
        </p>
        <div className="grid grid-cols-2 gap-2 text-xs text-gray-600">
          <p>Начислено (до ограничения): {violationResolution.rawPenaltyAmount} сом</p>
          <p>Применено (с учётом лимита): {violationResolution.appliedPenaltyAmount} сом</p>
          <p>Возврат продавцу: {violationResolution.refundAmount} сом</p>
          <p>Разрешено: {formatDateTime(violationResolution.resolvedAt)}</p>
        </div>
      </div>
    );
  }

  if (!eligible) {
    return null;
  }

  const toggle = (id: number) => {
    setSelectedIds(prev => (prev.includes(id) ? prev.filter(v => v !== id) : [...prev, id]));
  };

  const submit = async () => {
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/diagnostic/reports/${requestId}/seller-violation-resolution`, {
        violationDefinitionIds: selectedIds,
      });
      notify('Нарушение продавца разрешено');
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Уже разрешено (другим администратором или ранее) — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при разрешении нарушения', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-red-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Разрешить нарушение продавца</h3>

      {msg && (
        <div className={`text-xs rounded-lg px-3 py-1.5 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
          {msg.text}
        </div>
      )}

      <div className="space-y-1.5">
        <p className="text-xs text-gray-500">Нарушения продавца (из каталога политики):</p>
        {violations.length === 0 ? (
          <p className="text-xs text-gray-400">Загрузка каталога...</p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
            {violations.map(v => (
              <label key={v.id} className="flex items-center gap-2 text-xs text-gray-600 border rounded-lg px-2 py-1.5">
                <input type="checkbox" checked={selectedIds.includes(v.id)} onChange={() => toggle(v.id)} />
                <span>
                    {/* FE-UX-01: the description is what the admin reads; the code stays as a reference */}
                    <span className="text-gray-800">{violationLabel(v.code) ?? (v.description || v.code)}</span>
                    {(violationLabel(v.code) || v.description) && <span className="ml-1 font-mono text-gray-400">{v.code}</span>}
                  </span>
              </label>
            ))}
          </div>
        )}
      </div>

      {!confirming ? (
        <button onClick={() => setConfirming(true)} disabled={selectedIds.length === 0 || violations.length === 0}
          className="px-4 py-2 rounded-lg bg-red-600 text-white text-sm font-medium hover:bg-red-700 disabled:opacity-50">
          Разрешить нарушение
        </button>
      ) : (
        <div className="flex gap-2">
          <button onClick={submit} disabled={submitting}
            className="px-4 py-2 rounded-lg bg-red-700 text-white text-sm font-medium hover:bg-red-800 disabled:opacity-50">
            {submitting ? '...' : 'Подтвердить'}
          </button>
          <button onClick={() => setConfirming(false)} disabled={submitting}
            className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
            Отмена
          </button>
        </div>
      )}
    </div>
  );
}
