import { formatDateTime } from '../../utils/formatDate';

interface Props {
  status: string;
  paymentDeadline?: string | null;
  overdue?: boolean;
}

/**
 * Sale-UI: read-only status for the winning buyer of an auction that has moved past bidding
 * (WAITING_FOR_PAYMENT / SOLD / SALE_FAILED). Deliberately has no action of any kind - the
 * backend's entire sale-workflow API (payment/handover/complete/fail) is ADMIN-only, so there is
 * nothing for the buyer to submit here. paymentDeadline/overdue are server-derived (never
 * computed here) - see AuctionResponse.fromDomain.
 */
export function BuyerSaleStatusPanel({ status, paymentDeadline, overdue }: Props) {
  if (status === 'WAITING_FOR_PAYMENT') {
    return (
      <div className="p-4 rounded-xl border border-blue-200 bg-blue-50 space-y-1.5">
        <p className="text-sm font-semibold text-blue-800">Вы выиграли аукцион</p>
        <p className="text-xs text-blue-700">
          Ожидается подтверждение оплаты и передачи автомобиля администратором.
        </p>
        {paymentDeadline && (
          <p className={`text-xs font-medium ${overdue ? 'text-red-600' : 'text-blue-600'}`}>
            Срок оплаты: {formatDateTime(paymentDeadline)}
            {overdue && ' (срок истёк — обратитесь к администратору)'}
          </p>
        )}
      </div>
    );
  }

  if (status === 'SOLD') {
    return (
      <div className="p-4 rounded-xl border border-purple-200 bg-purple-50">
        <p className="text-sm font-semibold text-purple-800">Продажа завершена</p>
        <p className="text-xs text-purple-700 mt-1">
          Оплата и передача автомобиля подтверждены администратором.
        </p>
      </div>
    );
  }

  if (status === 'SALE_FAILED') {
    return (
      <div className="p-4 rounded-xl border border-red-200 bg-red-50">
        <p className="text-sm font-semibold text-red-800">Сделка не состоялась</p>
        <p className="text-xs text-red-700 mt-1">
          Обратитесь к администратору за подробностями.
        </p>
      </div>
    );
  }

  return null;
}
