import { useEffect, useRef, useState } from 'react';
import api from '../../api/client';
import { apiErrorMessage } from '../../api/errors';
import { labelOf } from '../../domain/parse';
import {
  WITHDRAWAL_STATUS_LABELS, parseWithdrawalStatus, withdrawalAdminActions, type WithdrawalStatus,
} from '../../domain/withdrawal';
import { formatDateTime } from '../../utils/formatDate';

export interface AdminWithdrawalRequest {
  id: number;
  userId: number;
  username: string | null;
  email: string | null;
  amount: number;
  status: string;
  createdAt: string;
  processingStartedAt: string | null;
  processingStartedBy: number | null;
  processedAt: string | null;
  processedBy: number | null;
  rejectionReason: string | null;
  payoutNote: string | null;
}

const FILTERS: { value: WithdrawalStatus | ''; label: string }[] = [
  { value: 'PENDING', label: 'Ожидают' },
  { value: 'PROCESSING', label: 'В обработке' },
  { value: 'PAID', label: 'Выплачены' },
  { value: 'REJECTED', label: 'Отклонены' },
  { value: '', label: 'Все' },
];

/**
 * F6/F6.1: manual payout requests. Until a bank is connected AlSo pays withdrawals by hand:
 * "В работу" (PENDING -> PROCESSING, nothing paid yet), "Выплачено" (PROCESSING -> PAID - the
 * admin confirms the manual payout with a mandatory note; final), "Отклонить" (PENDING or
 * PROCESSING - mandatory reason and an explicit confirmation that no payout was made; the amount
 * goes back to the user's deposit). The reject request carries the status shown on screen, so the
 * server refuses it if the request changed meanwhile. The server refuses every other transition.
 */
export function AdminWithdrawalsPage() {
  const [filter, setFilter] = useState<WithdrawalStatus | ''>('PENDING');
  const [items, setItems] = useState<AdminWithdrawalRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);
  const [form, setForm] = useState<{ id: number; kind: 'reject' | 'paid' } | null>(null);
  const [reason, setReason] = useState('');
  const [noPayoutConfirmed, setNoPayoutConfirmed] = useState(false);
  const [payoutNote, setPayoutNote] = useState('');
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  // FE-WD-01: a failed or malformed queue is an error with a retry - never "Заявок нет." (an admin
  // must not conclude there is nothing to pay out because the list did not load).
  const [loadError, setLoadError] = useState('');
  const inFlight = useRef(false);

  const load = (status: WithdrawalStatus | '' = filter) => {
    setLoading(true);
    api.get('/admin/withdrawals', { params: status ? { status } : {} })
      .then(r => {
        if (!Array.isArray(r.data)) throw new Error('Malformed withdrawals response');
        setItems(r.data as AdminWithdrawalRequest[]);
        setLoadError('');
      })
      .catch(e => {
        setItems([]);
        setLoadError(apiErrorMessage(e, 'Не удалось загрузить заявки на вывод'));
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(filter); }, [filter]);

  const act = async (id: number, action: 'processing' | 'paid' | 'reject', okText: string, body?: object) => {
    // one state change at a time: the server refuses repeats, but a second click must not even send one
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(id);
    setMsg(null);
    try {
      await api.post(`/admin/withdrawals/${id}/${action}`, body);
      setMsg({ text: okText, ok: true });
      closeForm();
      load();
    } catch (e) {
      setMsg({ text: apiErrorMessage(e, 'Не удалось выполнить действие'), ok: false });
      load();
    } finally {
      inFlight.current = false;
      setBusy(null);
    }
  };

  const closeForm = () => {
    setForm(null);
    setReason('');
    setNoPayoutConfirmed(false);
    setPayoutNote('');
  };

  const openForm = (id: number, kind: 'reject' | 'paid') => {
    const same = form?.id === id && form.kind === kind;
    closeForm();
    if (!same) setForm({ id, kind });
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
        ) : loadError ? (
          <div role="alert" className="p-4 text-sm text-red-700 bg-red-50 space-y-2">
            <p>{loadError}</p>
            <button onClick={() => load()} className="px-3 py-1.5 bg-white border border-red-300 rounded-lg text-xs font-medium">
              Повторить
            </button>
          </div>
        ) : items.length === 0 ? (
          <p className="text-sm text-gray-400 p-4">Заявок нет.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {items.map(w => {
              const status = parseWithdrawalStatus(w.status);
              const actions = withdrawalAdminActions(status);
              const seen = status.known ? status.value : null;
              return (
              <div key={w.id} className="px-4 py-3 text-sm space-y-2" data-testid={`withdrawal-${w.id}`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-medium text-gray-800">
                    #{w.id} · {Number(w.amount).toLocaleString('ru')} сом · <span className="break-all">{w.username || `ID ${w.userId}`}</span>
                    {w.email && <span className="text-gray-500 font-normal break-all"> ({w.email})</span>}
                  </p>
                  <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-gray-100 text-gray-700">{labelOf(WITHDRAWAL_STATUS_LABELS, status)}</span>
                </div>
                <p className="text-xs text-gray-500">
                  Создана: {formatDateTime(w.createdAt)}
                  {w.processingStartedAt && ` · В работе с ${formatDateTime(w.processingStartedAt)} (админ #${w.processingStartedBy})`}
                  {w.processedAt && ` · Завершена ${formatDateTime(w.processedAt)} (админ #${w.processedBy})`}
                </p>
                {w.rejectionReason && <p className="text-xs text-red-600">Причина отклонения: {w.rejectionReason}</p>}

                {w.payoutNote && <p className="text-xs text-green-700">Заметка о выплате: {w.payoutNote}</p>}

                <div className="flex flex-wrap gap-2">
                  {actions.includes('processing') && (
                    <button disabled={busy === w.id} onClick={() => act(w.id, 'processing', `Заявка #${w.id} взята в работу`)}
                      className="text-xs px-3 py-1.5 rounded-lg bg-blue-600 text-white disabled:opacity-50">В работу</button>
                  )}
                  {actions.includes('paid') && (
                    <button disabled={busy === w.id} onClick={() => openForm(w.id, 'paid')}
                      className="text-xs px-3 py-1.5 rounded-lg bg-green-600 text-white disabled:opacity-50">Выплачено</button>
                  )}
                  {actions.includes('reject') && (
                    <button disabled={busy === w.id} onClick={() => openForm(w.id, 'reject')}
                      className="text-xs px-3 py-1.5 rounded-lg border text-red-600 disabled:opacity-50">Отклонить</button>
                  )}
                </div>

                {form?.id === w.id && form.kind === 'reject' && actions.includes('reject') && (
                  <div className="bg-gray-50 border border-gray-100 rounded-xl p-3 space-y-2" data-testid={`reject-form-${w.id}`}>
                    <textarea value={reason} onChange={e => setReason(e.target.value)} maxLength={1000} rows={2}
                      aria-label="Причина отклонения" placeholder="Причина отклонения (увидит пользователь; без номеров карт и счетов)"
                      className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                    <label className="flex items-start gap-2 text-xs text-gray-700">
                      <input type="checkbox" checked={noPayoutConfirmed} onChange={e => setNoPayoutConfirmed(e.target.checked)} className="mt-0.5" />
                      <span>Подтверждаю, что выплата пользователю по этой заявке фактически не производилась. Сумма вернётся на депозит пользователя.</span>
                    </label>
                    <button disabled={!reason.trim() || !noPayoutConfirmed || busy === w.id}
                      onClick={() => act(w.id, 'reject', `Заявка #${w.id} отклонена, сумма возвращена на депозит`,
                        { reason: reason.trim(), payoutNotMade: true, expectedStatus: seen })}
                      className="text-xs px-3 py-1.5 rounded-lg bg-red-600 text-white disabled:opacity-50">Отклонить и вернуть сумму</button>
                  </div>
                )}

                {form?.id === w.id && form.kind === 'paid' && actions.includes('paid') && (
                  <div className="bg-gray-50 border border-gray-100 rounded-xl p-3 space-y-2" data-testid={`paid-form-${w.id}`}>
                    <textarea value={payoutNote} onChange={e => setPayoutNote(e.target.value)} maxLength={1000} rows={2}
                      aria-label="Заметка о выплате" placeholder="Как и когда выплачено: способ, дата, номер обращения или чека"
                      className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                    <p className="text-xs text-amber-700">Не указывайте полный номер карты, счёта или другие банковские реквизиты.</p>
                    <button disabled={!payoutNote.trim() || busy === w.id}
                      onClick={() => act(w.id, 'paid', `Заявка #${w.id} отмечена выплаченной`, { payoutNote: payoutNote.trim() })}
                      className="text-xs px-3 py-1.5 rounded-lg bg-green-600 text-white disabled:opacity-50">
                      Подтвердить выплату {Number(w.amount).toLocaleString('ru')} сом
                    </button>
                  </div>
                )}
              </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
