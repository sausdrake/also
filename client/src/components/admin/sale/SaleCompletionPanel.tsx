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
 * Sale-UI: POST /{auctionId}/complete - WAITING_FOR_PAYMENT -> SOLD. No commission/deposit-
 * release/seller-commitment logic here at all; the server computes every financial consequence.
 */
export function SaleCompletionPanel({ auctionId, status, onDone, notify }: Props) {
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);

  if (status.auctionStatus !== 'WAITING_FOR_PAYMENT' || !status.paymentEvidence || !status.handoverEvidence) {
    return null;
  }

  const submit = async () => {
    setSubmitting(true);
    setConfirming(false);
    try {
      await api.post(`/admin/sale/${auctionId}/complete`, {});
      notify('Продажа завершена — аукцион переведён в SOLD');
      onDone();
    } catch (e: any) {
      if (e.response?.status === 409) {
        notify('Продажа уже завершена — статус обновлён', false);
      } else {
        notify(e.response?.data?.message || 'Ошибка при завершении продажи', false);
      }
      onDone();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="bg-white border border-green-200 bg-green-50/40 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Завершить продажу</h3>
      <p className="text-xs text-gray-500">
        Оплата и передача зафиксированы. Завершение необратимо переводит аукцион в статус SOLD.
      </p>
      {!confirming ? (
        <button onClick={() => setConfirming(true)}
          className="px-4 py-2 rounded-lg bg-green-600 text-white text-sm font-medium hover:bg-green-700">
          Завершить продажу
        </button>
      ) : (
        <div className="flex gap-2">
          <button onClick={submit} disabled={submitting}
            className="px-4 py-2 rounded-lg bg-green-700 text-white text-sm font-medium hover:bg-green-800 disabled:opacity-50">
            {submitting ? '...' : 'Подтвердить завершение'}
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
