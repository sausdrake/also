import { useRef, useState } from 'react';
import api from '../../../api/client';

type CorrectionType = 'AMOUNT_CORRECTION' | 'SELLER_CORRECTION' | 'PAYOUT_INVALIDATED';

interface Props {
  auctionId: number;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

/** The only three correction types the backend accepts - never invent a fourth. */
const TYPES: { value: CorrectionType; label: string }[] = [
  { value: 'AMOUNT_CORRECTION', label: 'Корректировка суммы' },
  { value: 'SELLER_CORRECTION', label: 'Корректировка продавца' },
  { value: 'PAYOUT_INVALIDATED', label: 'Выплата признана недействительной' },
];

/**
 * Sale-UI: POST /{auctionId}/settlement/corrections - only reachable once settlement.status =
 * PAYOUT_RECORDED (backend enforces this independently with a 409; the parent panel only renders
 * this form in that state, but that's a UX hint, not the actual guard). Creates an immutable
 * audit record; never mutates the original Settlement row and never computes/displays an
 * "effective"/"current" total (SettlementEffectiveState stays domain-owned, unexposed in this
 * phase).
 *
 * idempotencyKey is generated client-side and kept stable for the current in-progress attempt -
 * fingerprinted on (correctionType, correctedAmount, correctedSellerId, reason) so a retry of the
 * same logical submission reuses the same key, and only a genuinely new/changed submission gets a
 * fresh one. Mirrors the established pattern in ProfilePage.tsx's withdrawal-key handling
 * (withdrawKeyRef), the only existing precedent for a client-generated idempotency key in this
 * codebase.
 */
export function CorrectionForm({ auctionId, onDone, notify }: Props) {
  const [correctionType, setCorrectionType] = useState<CorrectionType | ''>('');
  const [correctedAmount, setCorrectedAmount] = useState('');
  const [correctedSellerId, setCorrectedSellerId] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const keyRef = useRef<{ fingerprint: string; key: string } | null>(null);
  const getIdempotencyKey = (fingerprint: string): string => {
    if (keyRef.current?.fingerprint !== fingerprint) {
      keyRef.current = { fingerprint, key: crypto.randomUUID() };
    }
    return keyRef.current.key;
  };

  const amountValid = correctionType !== 'AMOUNT_CORRECTION'
    || (correctedAmount.trim() !== '' && !isNaN(parseFloat(correctedAmount)) && parseFloat(correctedAmount) > 0);
  const sellerValid = correctionType !== 'SELLER_CORRECTION'
    || (correctedSellerId.trim() !== '' && /^\d+$/.test(correctedSellerId.trim()));
  const canSubmit = correctionType !== '' && reason.trim() !== '' && amountValid && sellerValid;

  const resetForm = () => {
    setCorrectionType('');
    setCorrectedAmount('');
    setCorrectedSellerId('');
    setReason('');
    setConfirming(false);
    keyRef.current = null;
  };

  const submit = async () => {
    if (!canSubmit) return;
    const fingerprint = JSON.stringify({ correctionType, correctedAmount, correctedSellerId, reason });
    const idempotencyKey = getIdempotencyKey(fingerprint);
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/sale/${auctionId}/settlement/corrections`, {
        correctionType,
        correctedAmount: correctionType === 'AMOUNT_CORRECTION' ? parseFloat(correctedAmount) : undefined,
        correctedSellerId: correctionType === 'SELLER_CORRECTION' ? Number(correctedSellerId) : undefined,
        reason: reason.trim(),
        idempotencyKey,
      });
      notify('Корректировка зафиксирована');
      resetForm();
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Конфликт при фиксации корректировки (уже применена или изменилось состояние) — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при фиксации корректировки', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-amber-200 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Добавить корректировку расчёта</h3>
      <p className="text-xs text-gray-500">
        Корректировка создаёт неизменяемую запись в истории расчёта поверх уже зафиксированной
        выплаты — она не изменяет саму первоначальную запись о выплате.
      </p>

      <select value={correctionType}
        onChange={e => { setCorrectionType(e.target.value as CorrectionType | ''); setConfirming(false); }}
        disabled={submitting}
        className="w-full border rounded-lg px-3 py-2 text-sm">
        <option value="">Выберите тип корректировки...</option>
        {TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
      </select>

      {correctionType === 'AMOUNT_CORRECTION' && (
        <input type="number" value={correctedAmount}
          onChange={e => { setCorrectedAmount(e.target.value); setConfirming(false); }}
          placeholder="Скорректированная сумма, сом" disabled={submitting}
          className="w-full border rounded-lg px-3 py-2 text-sm" />
      )}

      {correctionType === 'SELLER_CORRECTION' && (
        <input type="number" value={correctedSellerId}
          onChange={e => { setCorrectedSellerId(e.target.value); setConfirming(false); }}
          placeholder="ID продавца" disabled={submitting}
          className="w-full border rounded-lg px-3 py-2 text-sm" />
      )}

      <textarea value={reason} onChange={e => { setReason(e.target.value); setConfirming(false); }}
        placeholder="Причина корректировки (обязательно)" disabled={submitting} rows={2}
        className="w-full border rounded-lg px-3 py-2 text-sm" />

      {!confirming ? (
        <button onClick={() => canSubmit && setConfirming(true)} disabled={!canSubmit || submitting}
          className="px-4 py-2 rounded-lg bg-amber-600 text-white text-sm font-medium hover:bg-amber-700 disabled:opacity-50">
          Добавить корректировку
        </button>
      ) : (
        <div className="space-y-2">
          <div className="text-xs bg-amber-50 text-amber-700 rounded-lg px-3 py-2">
            {TYPES.find(t => t.value === correctionType)?.label}
            {correctionType === 'AMOUNT_CORRECTION' && ` · ${parseFloat(correctedAmount).toLocaleString('ru')} сом`}
            {correctionType === 'SELLER_CORRECTION' && ` · продавец #${correctedSellerId}`}
            {' · '}«{reason.trim()}»
            <br />Это действие создаст неизменяемую запись в истории расчёта.
          </div>
          <div className="flex gap-2">
            <button onClick={submit} disabled={submitting}
              className="px-4 py-2 rounded-lg bg-amber-700 text-white text-sm font-medium hover:bg-amber-800 disabled:opacity-50">
              {submitting ? '...' : 'Подтвердить корректировку'}
            </button>
            <button onClick={() => setConfirming(false)} disabled={submitting}
              className="px-4 py-2 rounded-lg border text-sm text-gray-600 hover:bg-gray-50">
              Отмена
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
