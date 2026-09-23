import { useState } from 'react';
import api from '../../../api/client';
import type { SettlementResponse } from '../../../pages/admin/AdminSalePage';

interface Props {
  auctionId: number;
  settlement: SettlementResponse;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

/**
 * Sale-UI: POST /{auctionId}/settlement/payout - attestedAmount is an administrator's cash-
 * handover attestation, never an electronic transfer (no bank/provider is involved). The backend
 * never compares attestedAmount against expectedSellerAmount - a discrepancy is legal and
 * preserved (SettlementService.recordPayout) - so this form never blocks submission on a
 * mismatch, only shows it as informational context.
 */
export function PayoutForm({ auctionId, settlement, onDone, notify }: Props) {
  const [amount, setAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const parsed = parseFloat(amount);
  const isValid = amount.trim() !== '' && !isNaN(parsed) && parsed > 0;
  const differsFromExpected = isValid && Math.abs(parsed - settlement.expectedSellerAmount) > 0.005;

  const submit = async () => {
    if (!isValid) {
      notify('Введите корректную сумму выплаты', false);
      return;
    }
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/sale/${auctionId}/settlement/payout`, { attestedAmount: parsed });
      notify('Выплата продавцу зафиксирована');
      setAmount('');
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Выплата уже зафиксирована с другой суммой — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при фиксации выплаты', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Зафиксировать выплату продавцу</h3>
      <p className="text-xs text-gray-500">
        Укажите сумму, фактически переданную продавцу наличными. Система фиксирует только сам
        факт передачи денег администратором — это не электронный перевод.
      </p>

      <div className="flex gap-2">
        <input
          type="number"
          value={amount}
          onChange={e => { setAmount(e.target.value); setConfirming(false); }}
          placeholder="Сумма выплаты, сом"
          disabled={submitting}
          className="flex-1 border rounded-lg px-3 py-2 text-sm"
        />
        {!confirming && (
          <button onClick={() => isValid && setConfirming(true)} disabled={!isValid || submitting}
            className="px-4 py-2 rounded-lg bg-green-600 text-white text-sm font-medium hover:bg-green-700 disabled:opacity-50">
            Зафиксировать
          </button>
        )}
      </div>

      {differsFromExpected && (
        <p className="text-xs text-amber-600">
          Отличается от ожидаемой суммы {settlement.expectedSellerAmount.toLocaleString('ru')} сом.
        </p>
      )}

      {confirming && (
        <div className="flex gap-2">
          <button onClick={submit} disabled={submitting}
            className="px-4 py-2 rounded-lg bg-green-700 text-white text-sm font-medium hover:bg-green-800 disabled:opacity-50">
            {submitting ? '...' : `Подтвердить выплату ${parsed.toLocaleString('ru')} сом`}
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
