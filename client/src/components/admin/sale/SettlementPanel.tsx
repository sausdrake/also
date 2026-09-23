import type { SaleStatus, SettlementCorrectionResponse } from '../../../pages/admin/AdminSalePage';
import { formatDateTime } from '../../../utils/formatDate';
import { PayoutForm } from './PayoutForm';
import { CorrectionForm } from './CorrectionForm';

interface Props {
  auctionId: number;
  status: SaleStatus;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

const CORRECTION_TYPE_LABEL: Record<string, string> = {
  AMOUNT_CORRECTION: 'Корректировка суммы',
  SELLER_CORRECTION: 'Корректировка продавца',
  PAYOUT_INVALIDATED: 'Выплата признана недействительной',
};

function CorrectionRow({ c }: { c: SettlementCorrectionResponse }) {
  return (
    <div className="text-xs text-gray-600 border-b border-gray-50 pb-1.5">
      <span className="font-medium text-gray-700">{CORRECTION_TYPE_LABEL[c.correctionType] ?? c.correctionType}</span>
      {c.correctedAmount != null && ` · ${c.correctedAmount.toLocaleString('ru')} сом`}
      {c.correctedSellerId != null && ` · продавец #${c.correctedSellerId}`}
      {' · «'}{c.reason}{'»'}
      {' · админ #'}{c.correctedByAdminId}
      {' · '}{formatDateTime(c.correctedAt)}
    </div>
  );
}

/**
 * Sale-UI: renders the settlement/payout workflow only once the sale actually SUCCEEDED
 * (Settlement is never created for a failed/no-sale outcome - SettlementService.createPending is
 * only ever invoked from the successful-sale completion path). Absent settlement (null) is a
 * legitimate state - either the sale hasn't been completed yet, or a legacy SOLD auction
 * predating this feature - never treated as an error, never invented client-side.
 *
 * SettlementEffectiveState (the backend's fold of corrections over the original payout) is
 * deliberately NOT exposed by the read model in this phase - this panel never reconstructs it.
 * Once any correction exists, the original attested payout is labeled "первоначально
 * зафиксированная" (originally recorded), never "текущая"/"итоговая"/"действующая" (current/
 * final/effective), and the correction history is always shown alongside it - never collapsed -
 * so an admin sees the full picture rather than trusting a single possibly-superseded number.
 */
export function SettlementPanel({ auctionId, status, onDone, notify }: Props) {
  if (status.saleResolution?.result !== 'SUCCESS') {
    return null;
  }

  const settlement = status.settlement;
  const corrections = status.settlementCorrections;

  if (!settlement) {
    return (
      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5">
        <h3 className="font-semibold text-gray-800 text-sm mb-1">Расчёт с продавцом</h3>
        <p className="text-xs text-gray-400">Запись о расчёте ещё не создана для этого аукциона.</p>
      </div>
    );
  }

  const hasCorrections = corrections.length > 0;
  const payoutLabel = hasCorrections ? 'Первоначально зафиксированная выплата' : 'Зафиксированная выплата';

  return (
    <div className="space-y-4">
      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
        <h3 className="font-semibold text-gray-800 text-sm">Расчёт с продавцом</h3>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-sm">
          <div className="p-3 rounded-xl border border-gray-100 bg-gray-50">
            <p className="text-xs text-gray-500">Сумма покупателя (ожидаемая)</p>
            <p className="font-semibold text-gray-700">{settlement.expectedBuyerPaidAmount.toLocaleString('ru')} сом</p>
          </div>
          <div className="p-3 rounded-xl border border-gray-100 bg-gray-50">
            <p className="text-xs text-gray-500">Сумма продавцу (ожидаемая)</p>
            <p className="font-semibold text-gray-700">{settlement.expectedSellerAmount.toLocaleString('ru')} сом</p>
          </div>
          <div className="p-3 rounded-xl border border-gray-100 bg-gray-50">
            <p className="text-xs text-gray-500">Комиссия (ожидаемая)</p>
            <p className="font-semibold text-gray-700">{settlement.expectedCommission.toLocaleString('ru')} сом</p>
          </div>
        </div>
        <p className="text-xs text-gray-400">
          Ожидаемые суммы рассчитаны сервером на основании условий аукциона.
        </p>

        {settlement.status === 'PAYOUT_RECORDED' && settlement.attestedPayoutAmount != null && (
          <div className="p-3 rounded-xl border border-green-200 bg-green-50">
            <p className="text-xs text-gray-500">{payoutLabel}</p>
            <p className="font-semibold text-green-700">{settlement.attestedPayoutAmount.toLocaleString('ru')} сом</p>
            <p className="text-xs text-gray-400">
              {settlement.recordedByAdminId != null && `Админ #${settlement.recordedByAdminId}`}
              {settlement.recordedAt && ` · ${formatDateTime(settlement.recordedAt)}`}
            </p>
          </div>
        )}
      </div>

      {settlement.status === 'PENDING' && (
        <PayoutForm auctionId={auctionId} settlement={settlement} onDone={onDone} notify={notify} />
      )}

      {settlement.status === 'PAYOUT_RECORDED' && (
        <CorrectionForm auctionId={auctionId} onDone={onDone} notify={notify} />
      )}

      {hasCorrections && (
        <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-2">
          <h3 className="font-semibold text-gray-800 text-sm">История корректировок</h3>
          <div className="space-y-1.5">
            {corrections.map(c => <CorrectionRow key={c.id} c={c} />)}
          </div>
        </div>
      )}
    </div>
  );
}
