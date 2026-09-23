import { useState } from 'react';
import api from '../../../api/client';
import type { SaleStatus } from '../../../pages/admin/AdminSalePage';

interface Props {
  auctionId: number;
  status: SaleStatus;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

/**
 * Sale-UI: POST /{auctionId}/payment - amount only, no receipt/media (PaymentEvidence has no
 * media field, see the backend DTO). Hidden once payment is already recorded per the
 * authoritative GET response - the button is not the only guard, the server's own
 * existsByAuctionId check (409) is; on 409 this refetches rather than trusting local state.
 */
export function PaymentEvidenceForm({ auctionId, status, onDone, notify }: Props) {
  const [amount, setAmount] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (status.auctionStatus !== 'WAITING_FOR_PAYMENT' || status.paymentEvidence) {
    return null;
  }

  const submit = async () => {
    const val = parseFloat(amount);
    if (!amount.trim() || isNaN(val) || val <= 0) {
      notify('Введите корректную сумму', false);
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/admin/sale/${auctionId}/payment`, { amount: val });
      notify('Оплата зафиксирована');
      setAmount('');
      onDone();
    } catch (e: any) {
      const status = e.response?.status;
      if (status === 409) {
        notify('Оплата уже зафиксирована другим администратором — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при фиксации оплаты', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Зафиксировать оплату</h3>
      <div className="flex gap-2">
        <input
          type="number"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          placeholder="Сумма, сом"
          disabled={submitting}
          className="flex-1 border rounded-lg px-3 py-2 text-sm"
        />
        <button onClick={submit} disabled={submitting}
          className="px-4 py-2 rounded-lg bg-green-600 text-white text-sm font-medium hover:bg-green-700 disabled:opacity-50">
          {submitting ? '...' : 'Зафиксировать'}
        </button>
      </div>
    </div>
  );
}
