import { useEffect, useState } from 'react';
import api from '../../api/client';
import { violationLabel } from '../../domain/violation';

interface ViolationDefinition {
  id: number;
  code: string;
  description: string;
}

interface Props {
  auctionId: number;
  onDone: (message: string, isError?: boolean) => void;
  onClose: () => void;
}

/**
 * AUC-NOSALE-01: cancelling a lot. Without seller fault the participants' deposits and the seller's guarantee are
 * returned in full; for a confirmed seller violation the admin picks the violations - the penalty set by the lot's
 * policy is withheld once and the rest is returned. Either way the vehicle goes to re-diagnostic.
 */
export function CancelAuctionModal({ auctionId, onDone, onClose }: Props) {
  const [sellerFault, setSellerFault] = useState(false);
  const [violations, setViolations] = useState<ViolationDefinition[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (!sellerFault || violations.length > 0) return;
    api.get<ViolationDefinition[]>('/admin/policy/violation-definitions')
      .then(r => setViolations(r.data))
      .catch(() => setViolations([]));
  }, [sellerFault, violations.length]);

  const toggle = (id: number) => setSelected(s => s.includes(id) ? s.filter(x => x !== id) : [...s, id]);

  const submit = async () => {
    setSending(true);
    try {
      await api.post(`/admin/auctions/${auctionId}/cancel`, sellerFault ? { violationDefinitionIds: selected } : undefined);
      onDone(sellerFault
        ? `Аукцион #${auctionId} отменён: штраф по нарушению удержан из гарантии продавца, остаток возвращён`
        : `Аукцион #${auctionId} отменён`);
    } catch (e: any) {
      onDone(e?.response?.data?.message || 'Ошибка', true);
    } finally {
      setSending(false);
    }
  };

  return (
    <div role="dialog" aria-modal="true" className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-xl max-w-md w-full p-6 space-y-4">
        <p className="text-gray-800">Отменить аукцион #{auctionId}? Действие необратимо. Депозиты участников вернутся,
          автомобиль уйдёт на повторную диагностику.</p>
        <fieldset className="space-y-2 text-sm">
          <label className="flex items-start gap-2">
            <input type="radio" name="fault" checked={!sellerFault} onChange={() => setSellerFault(false)} className="mt-0.5" />
            <span>Без вины продавца — гарантия продавца возвращается полностью</span>
          </label>
          <label className="flex items-start gap-2">
            <input type="radio" name="fault" checked={sellerFault} onChange={() => setSellerFault(true)} className="mt-0.5" />
            <span>Подтверждённое нарушение продавца — штраф по политике, остаток гарантии возвращается</span>
          </label>
        </fieldset>
        {sellerFault && (
          <div className="space-y-1 max-h-48 overflow-y-auto border border-gray-100 rounded-lg p-2">
            {violations.length === 0 ? <p className="text-xs text-gray-400">Загрузка перечня нарушений…</p> : violations.map(v => (
              <label key={v.id} className="flex items-start gap-2 text-sm">
                <input type="checkbox" checked={selected.includes(v.id)} onChange={() => toggle(v.id)} className="mt-0.5" />
                <span>{violationLabel(v.code) ?? v.description ?? v.code}</span>
              </label>
            ))}
          </div>
        )}
        <div className="flex gap-2 justify-end">
          <button onClick={onClose} className="px-4 py-2 border border-gray-200 rounded-lg text-sm hover:bg-gray-50">Отмена</button>
          <button onClick={submit} disabled={sending || (sellerFault && selected.length === 0)}
            className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700 disabled:opacity-50">
            {sending ? 'Отмена…' : 'Подтвердить'}
          </button>
        </div>
      </div>
    </div>
  );
}
