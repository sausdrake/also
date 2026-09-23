import { useCallback, useEffect, useState } from 'react';
import api from '../../../api/client';
import { formatDateTime } from '../../../utils/formatDate';
import type { SaleStatus } from '../../../pages/admin/AdminSalePage';

interface Props {
  auctionId: number;
  status: SaleStatus;
  onDone: () => void;
  notify: (text: string, ok?: boolean) => void;
}

interface DeadlineHistoryEntry {
  id: number;
  changeType: string;
  previousDeadline: string | null;
  newDeadline: string;
  reason: string | null;
  changedByAdminId: number;
  changedAt: string;
}

/**
 * Sale-UI: POST /{auctionId}/deadline/extend + GET .../deadline/history. Only shown while
 * WAITING_FOR_PAYMENT (server rule, mirrored here only as a UX hint). No working-day/deadline
 * math performed client-side - the admin picks an absolute datetime, the server validates it.
 */
export function PaymentDeadlineExtendForm({ auctionId, status, onDone, notify }: Props) {
  const [newDeadline, setNewDeadline] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [history, setHistory] = useState<DeadlineHistoryEntry[]>([]);
  const [showHistory, setShowHistory] = useState(false);

  const loadHistory = useCallback(() => {
    api.get<DeadlineHistoryEntry[]>(`/admin/sale/${auctionId}/deadline/history`)
      .then(r => setHistory(r.data))
      .catch(() => {});
  }, [auctionId]);

  useEffect(() => { if (showHistory) loadHistory(); }, [showHistory, loadHistory]);

  const submit = async () => {
    if (!newDeadline || !reason.trim()) {
      notify('Укажите новый дедлайн и причину', false);
      return;
    }
    setSubmitting(true);
    try {
      await api.post(`/admin/sale/${auctionId}/deadline/extend`, {
        newDeadline: new Date(newDeadline).toISOString(),
        reason: reason.trim(),
      });
      notify('Платёжный дедлайн изменён');
      setNewDeadline('');
      setReason('');
      onDone();
      if (showHistory) loadHistory();
    } catch (e: any) {
      notify(e.response?.data?.message || 'Ошибка при изменении дедлайна', false);
    } finally {
      setSubmitting(false);
    }
  };

  if (status.auctionStatus !== 'WAITING_FOR_PAYMENT') {
    return (
      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5">
        <button onClick={() => setShowHistory(v => !v)}
          className="text-xs text-gray-500 hover:text-gray-700 underline">
          {showHistory ? 'Скрыть' : 'Показать'} историю дедлайна
        </button>
        {showHistory && <DeadlineHistoryList history={history} />}
      </div>
    );
  }

  return (
    <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-3">
      <h3 className="font-semibold text-gray-800 text-sm">Изменить платёжный дедлайн</h3>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <input type="datetime-local" value={newDeadline} onChange={e => setNewDeadline(e.target.value)}
          className="border rounded-lg px-3 py-2 text-sm" />
        <input type="text" value={reason} onChange={e => setReason(e.target.value)}
          placeholder="Причина (обязательно)" className="border rounded-lg px-3 py-2 text-sm" />
      </div>
      <button onClick={submit} disabled={submitting}
        className="px-4 py-2 rounded-lg bg-gray-700 text-white text-sm font-medium hover:bg-gray-800 disabled:opacity-50">
        {submitting ? '...' : 'Изменить дедлайн'}
      </button>

      <button onClick={() => setShowHistory(v => !v)}
        className="text-xs text-gray-500 hover:text-gray-700 underline block">
        {showHistory ? 'Скрыть' : 'Показать'} историю дедлайна
      </button>
      {showHistory && <DeadlineHistoryList history={history} />}
    </div>
  );
}

function DeadlineHistoryList({ history }: { history: DeadlineHistoryEntry[] }) {
  if (history.length === 0) {
    return <p className="text-xs text-gray-400 mt-2">История пуста.</p>;
  }
  return (
    <ul className="mt-2 space-y-1.5">
      {history.map(h => (
        <li key={h.id} className="text-xs text-gray-500 border-l-2 border-gray-200 pl-2">
          <span className="font-medium text-gray-700">{h.changeType}</span> → {formatDateTime(h.newDeadline)}
          {h.reason && <span className="text-gray-400"> · {h.reason}</span>}
          <span className="text-gray-300"> ({formatDateTime(h.changedAt)})</span>
        </li>
      ))}
    </ul>
  );
}
