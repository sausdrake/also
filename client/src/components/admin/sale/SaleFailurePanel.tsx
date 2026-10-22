import { useEffect, useState } from 'react';
import api from '../../../api/client';
import { violationLabel } from '../../../domain/violation';
import type { SaleStatus } from '../../../pages/admin/AdminSalePage';

interface Props {
  auctionId: number;
  status: SaleStatus;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

interface ViolationDefinition {
  id: number;
  code: string;
  description: string;
}

/** The only four outcomes the backend accepts on /fail - SUCCESS is never offered here (that's /complete's job). */
const OUTCOMES = [
  { value: 'B', label: 'B — обычная несостоявшаяся продажа (без вины сторон)' },
  { value: 'BUYER_FAULT', label: 'Вина покупателя' },
  { value: 'PLATFORM_FAULT', label: 'Вина платформы' },
  { value: 'C', label: 'Нарушение продавца' },
];

/**
 * Sale-UI: POST /{auctionId}/fail - outcome is a closed set matching the backend's own
 * SaleResolutionResult exactly; violationDefinitionIds (outcome=C only) are selected from the
 * already-existing GET /admin/policy/violation-definitions catalogue, never invented or
 * calculated client-side. No penalty/severity/percentage is computed here - the policy engine is
 * authoritative.
 */
export function SaleFailurePanel({ auctionId, status, onDone, notify }: Props) {
  const [outcome, setOutcome] = useState('');
  const [reason, setReason] = useState('');
  const [violations, setViolations] = useState<ViolationDefinition[]>([]);
  const [selectedViolationIds, setSelectedViolationIds] = useState<number[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (outcome === 'C' && violations.length === 0) {
      api.get<ViolationDefinition[]>('/admin/policy/violation-definitions')
        .then(r => setViolations(r.data))
        .catch(() => {});
    }
  }, [outcome, violations.length]);

  if (status.auctionStatus !== 'WAITING_FOR_PAYMENT') {
    return null;
  }

  const toggleViolation = (id: number) => {
    setSelectedViolationIds(prev =>
      prev.includes(id) ? prev.filter(v => v !== id) : [...prev, id]);
  };

  const submit = async () => {
    if (!outcome) {
      notify('Выберите исход', false);
      return;
    }
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/sale/${auctionId}/fail`, {
        outcome,
        violationDefinitionIds: outcome === 'C' ? selectedViolationIds : undefined,
        reason: reason.trim() || undefined,
      });
      notify('Сделка отмечена как несостоявшаяся');
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Продажа уже завершена — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при фиксации несостоявшейся продажи', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-red-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Отметить сделку как несостоявшуюся</h3>

      <select value={outcome} onChange={e => { setOutcome(e.target.value); setConfirming(false); }}
        className="w-full border rounded-lg px-3 py-2 text-sm">
        <option value="">Выберите исход...</option>
        {OUTCOMES.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>

      {outcome === 'C' && (
        <div className="space-y-1.5">
          <p className="text-xs text-gray-500">Нарушения продавца (из каталога политики):</p>
          {violations.length === 0 ? (
            <p className="text-xs text-gray-400">Загрузка каталога...</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
              {violations.map(v => (
                <label key={v.id} className="flex items-center gap-2 text-xs text-gray-600 border rounded-lg px-2 py-1.5">
                  <input type="checkbox" checked={selectedViolationIds.includes(v.id)}
                    onChange={() => toggleViolation(v.id)} />
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
      )}

      <textarea value={reason} onChange={e => setReason(e.target.value)}
        placeholder="Комментарий (необязательно)"
        className="w-full border rounded-lg px-3 py-2 text-sm" rows={2} />

      {!confirming ? (
        <button onClick={() => setConfirming(true)} disabled={!outcome}
          className="px-4 py-2 rounded-lg bg-red-600 text-white text-sm font-medium hover:bg-red-700 disabled:opacity-50">
          Отметить как несостоявшуюся
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
