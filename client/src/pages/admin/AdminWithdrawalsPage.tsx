import { useEffect, useState } from 'react';
import api from '../../api/client';
import { formatDateTime } from '../../utils/formatDate';

type WithdrawalStatus = 'PENDING' | 'PROCESSING' | 'PAID' | 'REJECTED';

export interface AdminWithdrawalRequest {
  id: number;
  userId: number;
  username: string | null;
  email: string | null;
  amount: number;
  status: WithdrawalStatus;
  createdAt: string;
  processingStartedAt: string | null;
  processingStartedBy: number | null;
  processedAt: string | null;
  processedBy: number | null;
  rejectionReason: string | null;
}

const STATUS_LABEL: Record<WithdrawalStatus, string> = {
  PENDING: 'Ожидает',
  PROCESSING: 'В обработке',
  PAID: 'Выплачено',
  REJECTED: 'Отклонено',
};

const FILTERS: { value: WithdrawalStatus | ''; label: string }[] = [
  { value: 'PENDING', label: 'Ожидают' },
  { value: 'PROCESSING', label: 'В обработке' },
  { value: 'PAID', label: 'Выплачены' },
  { value: 'REJECTED', label: 'Отклонены' },
  { value: '', label: 'Все' },
];

/**
 * F6: manual payout requests. Until a bank is connected AlSo pays withdrawals by hand:
 * "В работу" (PENDING -> PROCESSING, nothing paid yet), "Выплачено" (PROCESSING -> PAID - the
 * admin confirms the manual payout was made; final), "Отклонить" (PENDING only, mandatory reason,
 * the amount goes back to the user's deposit). The server refuses every other transition.
 */
export function AdminWithdrawalsPage() {
  const [filter, setFilter] = useState<WithdrawalStatus | ''>('PENDING');
  const [items, setItems] = useState<AdminWithdrawalRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);
  const [rejecting, setRejecting] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const load = (status: WithdrawalStatus | '' = filter) => {
    setLoading(true);
    api.get<AdminWithdrawalRequest[]>('/admin/withdrawals', { params: status ? { status } : {} })
      .then(r => setItems(Array.isArray(r.data) ? r.data : []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(filter); }, [filter]);

  const act = async (id: number, action: 'processing' | 'paid' | 'reject', okText: string, body?: object) => {
    setBusy(id);
    setMsg(null);
    try {
      await api.post(`/admin/withdrawals/${id}/${action}`, body);
      setMsg({ text: okText, ok: true });
      setRejecting(null);
      setReason('');
      load();
    } catch (e: any) {
      setMsg({ text: e.response?.data?.message || 'Не удалось выполнить действие', ok: false });
      load();
    } finally {
      setBusy(null);
    }
  };

  const markPaid = (w: AdminWithdrawalRequest) => {
    if (!window.confirm(`Подтвердить, что ${Number(w.amount).toLocaleString('ru')} сом выплачены пользователю вручную? Отменить это действие нельзя.`)) return;
    act(w.id, 'paid', `Заявка #${w.id} отмечена выплаченной`);
  };

  return (
    <div className="max-w-5xl mx-auto py-8 px-4 space-y-5">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Заявки на вывод средств</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Банк не подключён: выплату выполняет администратор вручную. Сумма заявки уже списана с депозита
          пользователя. «Выплачено» означает только подтверждение ручной выплаты.
        </p>
      </div>

      <div className="flex flex-wrap gap-2">
        {FILTERS.map(f => (
          <button key={f.label} onClick={() => setFilter(f.value)}
            className={`text-xs px-3 py-1.5 rounded-lg border ${filter === f.value ? 'bg-blue-600 text-white border-blue-600' : 'text-gray-600'}`}>
            {f.label}
          </button>
        ))}
      </div>

      {msg && (
        <div className={`text-sm rounded-lg px-3 py-2 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>{msg.text}</div>
      )}

      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
        {loading ? (
          <p className="text-sm text-gray-400 p-4">Загрузка...</p>
        ) : items.length === 0 ? (
          <p className="text-sm text-gray-400 p-4">Заявок нет.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {items.map(w => (
              <div key={w.id} className="px-4 py-3 text-sm space-y-2" data-testid={`withdrawal-${w.id}`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-medium text-gray-800">
                    #{w.id} · {Number(w.amount).toLocaleString('ru')} сом · {w.username || `ID ${w.userId}`}
                    {w.email && <span className="text-gray-500 font-normal"> ({w.email})</span>}
                  </p>
                  <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-gray-100 text-gray-700">{STATUS_LABEL[w.status]}</span>
                </div>
                <p className="text-xs text-gray-500">
                  Создана: {formatDateTime(w.createdAt)}
                  {w.processingStartedAt && ` · В работе с ${formatDateTime(w.processingStartedAt)} (админ #${w.processingStartedBy})`}
                  {w.processedAt && ` · Завершена ${formatDateTime(w.processedAt)} (админ #${w.processedBy})`}
                </p>
                {w.rejectionReason && <p className="text-xs text-red-600">Причина отклонения: {w.rejectionReason}</p>}

                <div className="flex flex-wrap gap-2">
                  {w.status === 'PENDING' && (
                    <>
                      <button disabled={busy === w.id} onClick={() => act(w.id, 'processing', `Заявка #${w.id} взята в работу`)}
                        className="text-xs px-3 py-1.5 rounded-lg bg-blue-600 text-white disabled:opacity-50">В работу</button>
                      <button disabled={busy === w.id} onClick={() => { setRejecting(rejecting === w.id ? null : w.id); setReason(''); }}
                        className="text-xs px-3 py-1.5 rounded-lg border text-red-600 disabled:opacity-50">Отклонить</button>
                    </>
                  )}
                  {w.status === 'PROCESSING' && (
                    <button disabled={busy === w.id} onClick={() => markPaid(w)}
                      className="text-xs px-3 py-1.5 rounded-lg bg-green-600 text-white disabled:opacity-50">Выплачено</button>
                  )}
                </div>

                {rejecting === w.id && w.status === 'PENDING' && (
                  <div className="bg-gray-50 border border-gray-100 rounded-xl p-3 space-y-2">
                    <textarea value={reason} onChange={e => setReason(e.target.value)} maxLength={1000} rows={2}
                      aria-label="Причина отклонения" placeholder="Причина отклонения (увидит пользователь)"
                      className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                    <button disabled={!reason.trim() || busy === w.id}
                      onClick={() => act(w.id, 'reject', `Заявка #${w.id} отклонена, сумма возвращена на депозит`, { reason: reason.trim() })}
                      className="text-xs px-3 py-1.5 rounded-lg bg-red-600 text-white disabled:opacity-50">Отклонить и вернуть сумму</button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
