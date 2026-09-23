import { useEffect, useState } from 'react';
import api from '../../api/client';
import { formatDateTime } from '../../utils/formatDate';

interface AuctionStatusInfo {
  id: number;
  status: string;
  currentPrice: number;
  winnerId: number | null;
  winningBid: number | null;
  paymentDeadline?: string | null;
}

const AUCTION_STATUS_LABEL: Record<string, string> = {
  CREATED: 'Ожидает начала',
  ACTIVE: 'Идут торги',
  WAITING_FOR_PAYMENT: 'Ожидание оплаты покупателем',
  SOLD: 'Продан',
  SALE_FAILED: 'Сделка не состоялась',
  CANCELLED: 'Отменён',
  EXPIRED: 'Завершён без покупателя',
};

/**
 * Sale-UI: read-only vehicle/auction visibility for the requester of an APPROVED diagnostic
 * request - there is no SELLER role, this is just the authenticated USER who submitted the
 * request. Shown only for APPROVED requests. Uses Gap #1 (vehicleId on the request response)
 * then Gap #2 (GET /vehicles/{vehicleId}/auction) - both newly-exposed reads, no new business
 * logic. Absence at either step is rendered as a plain informational state, never an error.
 */
export function RequestVehicleStatus({ vehicleId }: { vehicleId: number | null }) {
  const [auction, setAuction] = useState<AuctionStatusInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [notListedYet, setNotListedYet] = useState(false);

  useEffect(() => {
    if (!vehicleId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setNotListedYet(false);
    api.get<AuctionStatusInfo>(`/vehicles/${vehicleId}/auction`)
      .then(r => { if (!cancelled) setAuction(r.data); })
      .catch(e => {
        if (cancelled) return;
        if (e.response?.status === 404) setNotListedYet(true);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [vehicleId]);

  if (!vehicleId) {
    return (
      <p className="text-xs text-gray-400 mt-1">Автомобиль ещё не создан из этой заявки.</p>
    );
  }

  if (loading) {
    return <p className="text-xs text-gray-400 mt-1">Загрузка статуса аукциона...</p>;
  }

  if (notListedYet || !auction) {
    return (
      <p className="text-xs text-gray-400 mt-1">Автомобиль пока не выставлен на аукцион.</p>
    );
  }

  return (
    <div className="mt-2 p-3 rounded-lg bg-gray-50 border border-gray-100 space-y-1">
      <p className="text-xs font-medium text-gray-700">
        Аукцион: {AUCTION_STATUS_LABEL[auction.status] ?? auction.status}
      </p>
      {auction.winnerId && (
        <p className="text-xs text-gray-500">
          Покупатель определён{auction.winningBid != null && ` · ${Number(auction.winningBid).toLocaleString('ru')} сом`}
        </p>
      )}
      {auction.status === 'WAITING_FOR_PAYMENT' && auction.paymentDeadline && (
        <p className="text-xs text-gray-500">Срок оплаты: {formatDateTime(auction.paymentDeadline)}</p>
      )}
    </div>
  );
}
