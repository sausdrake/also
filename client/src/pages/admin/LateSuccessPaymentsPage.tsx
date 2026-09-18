import { useCallback, useEffect, useState } from 'react';
import api from '../../api/client';
import { formatDateTime } from '../../utils/formatDate';

interface AdminLateSuccessPayment {
  id: number;
  userId: number;
  amountRequested: number;
  currency: string;
  amountConfirmed: number | null;
  providerTransactionId: string | null;
  status: string;
  createdAt: string;
  expiresAt: string;
  updatedAt: string;
  lateSuccessAfterExpiry: boolean;
}

/**
 * Phase 26: admin-only, read-only observability list for payments whose provider success was
 * reported after the payment had already expired. Purely informational - the credit already
 * happened correctly and exactly once; this page exists only so an admin can inspect the fact.
 * Thin client of GET /api/v1/admin/payments/late-successes.
 */
export function LateSuccessPaymentsPage() {
  const [items, setItems] = useState<AdminLateSuccessPayment[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.get<AdminLateSuccessPayment[]>('/admin/payments/late-successes');
      setItems(r.data);
      setErr(false);
    } catch {
      setErr(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Поздние успешные платежи</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Платежи, по которым провайдер сообщил об успехе уже после истечения срока. Средства уже
          корректно зачислены ровно один раз - список только для наблюдения.
        </p>
      </div>

      {err && (
        <div className="text-sm rounded-xl px-4 py-2 bg-red-50 text-red-700">Ошибка загрузки</div>
      )}

      {loading ? (
        <div className="p-8 text-center text-gray-400">Загрузка...</div>
      ) : (
        <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-x-auto">
          {items.length === 0 ? (
            <div className="p-5 text-sm text-gray-400">Таких платежей нет.</div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-gray-500 border-b border-gray-100">
                  <th className="px-4 py-2 font-medium">ID</th>
                  <th className="px-4 py-2 font-medium">Пользователь</th>
                  <th className="px-4 py-2 font-medium">Запрошено</th>
                  <th className="px-4 py-2 font-medium">Подтверждено</th>
                  <th className="px-4 py-2 font-medium">Транзакция провайдера</th>
                  <th className="px-4 py-2 font-medium">Статус</th>
                  <th className="px-4 py-2 font-medium">Истёк</th>
                  <th className="px-4 py-2 font-medium">Успех зафиксирован</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {items.map((p) => (
                  <tr key={p.id}>
                    <td className="px-4 py-2 text-gray-800">{p.id}</td>
                    <td className="px-4 py-2 text-gray-800">{p.userId}</td>
                    <td className="px-4 py-2 text-gray-800">{p.amountRequested} {p.currency}</td>
                    <td className="px-4 py-2 text-gray-800">{p.amountConfirmed ?? '—'} {p.currency}</td>
                    <td className="px-4 py-2 text-gray-500">{p.providerTransactionId ?? '—'}</td>
                    <td className="px-4 py-2">
                      <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-green-100 text-green-800">
                        {p.status}
                      </span>
                    </td>
                    <td className="px-4 py-2 text-gray-500">{formatDateTime(p.expiresAt)}</td>
                    <td className="px-4 py-2 text-gray-500">{formatDateTime(p.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
