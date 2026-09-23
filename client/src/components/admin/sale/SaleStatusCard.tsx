import { formatDateTime } from '../../../utils/formatDate';
import type { SaleStatus } from '../../../pages/admin/AdminSalePage';

const STATUS_BADGE: Record<string, string> = {
  WAITING_FOR_PAYMENT: 'bg-amber-100 text-amber-700',
  SOLD: 'bg-purple-100 text-purple-700',
  SALE_FAILED: 'bg-red-100 text-red-700',
};

/** Sale-UI: pure display of the authoritative GET /admin/sale/{id} response - no business logic. */
export function SaleStatusCard({ status }: { status: SaleStatus }) {
  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <div className="flex items-center justify-between">
        <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${STATUS_BADGE[status.auctionStatus] ?? 'bg-gray-100 text-gray-600'}`}>
          {status.auctionStatus}
        </span>
        {status.paymentDeadline && (
          <span className={`text-xs ${status.overdue ? 'text-red-600 font-medium' : 'text-gray-500'}`}>
            Срок оплаты: {formatDateTime(status.paymentDeadline)}
            {status.overdue && ' · просрочен'}
          </span>
        )}
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-sm">
        <div className={`p-3 rounded-xl border ${status.paymentEvidence ? 'border-green-200 bg-green-50' : 'border-gray-100 bg-gray-50'}`}>
          <p className="text-xs text-gray-500">Оплата</p>
          {status.paymentEvidence ? (
            <>
              <p className="font-semibold text-green-700">{Number(status.paymentEvidence.amount).toLocaleString('ru')} сом</p>
              <p className="text-xs text-gray-400">{formatDateTime(status.paymentEvidence.receivedAt)}</p>
            </>
          ) : (
            <p className="text-gray-400">Не зафиксирована</p>
          )}
        </div>

        <div className={`p-3 rounded-xl border ${status.handoverEvidence ? 'border-green-200 bg-green-50' : 'border-gray-100 bg-gray-50'}`}>
          <p className="text-xs text-gray-500">Передача</p>
          {status.handoverEvidence ? (
            <p className="text-xs text-gray-400 mt-1">{formatDateTime(status.handoverEvidence.confirmedAt)}</p>
          ) : (
            <p className="text-gray-400">Не зафиксирована</p>
          )}
        </div>

        <div className={`p-3 rounded-xl border ${status.saleResolution ? 'border-purple-200 bg-purple-50' : 'border-gray-100 bg-gray-50'}`}>
          <p className="text-xs text-gray-500">Итог</p>
          {status.saleResolution ? (
            <>
              <p className="font-semibold text-purple-700">{status.saleResolution.result}</p>
              {status.saleResolution.faultParty && (
                <p className="text-xs text-gray-400">Ответственность: {status.saleResolution.faultParty}</p>
              )}
            </>
          ) : (
            <p className="text-gray-400">Не завершено</p>
          )}
        </div>
      </div>

      {status.saleResolution?.reason && (
        <p className="text-xs text-gray-500 border-t pt-2">Причина: {status.saleResolution.reason}</p>
      )}
    </div>
  );
}
