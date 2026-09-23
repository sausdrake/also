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
 * Sale-UI: POST /{auctionId}/handover - a single confirmation, no request body, no media.
 * Only rendered once payment evidence exists (server also enforces this precondition
 * independently, so this is a UX hint, not the actual guard).
 */
export function HandoverConfirmPanel({ auctionId, status, onDone, notify }: Props) {
  const [submitting, setSubmitting] = useState(false);

  if (status.auctionStatus !== 'WAITING_FOR_PAYMENT' || !status.paymentEvidence || status.handoverEvidence) {
    return null;
  }

  const submit = async () => {
    setSubmitting(true);
    try {
      await api.post(`/admin/sale/${auctionId}/handover`, {});
      notify('Передача автомобиля зафиксирована');
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Передача уже зафиксирована — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при фиксации передачи', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Зафиксировать передачу автомобиля</h3>
      <button onClick={submit} disabled={submitting}
        className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-50">
        {submitting ? '...' : 'Подтвердить передачу'}
      </button>
    </div>
  );
}
