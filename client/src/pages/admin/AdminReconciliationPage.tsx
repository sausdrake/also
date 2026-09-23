import { useCallback, useEffect, useState } from 'react';
import api from '../../api/client';
import { ReconciliationCaseList, type ReconciliationCaseListItem } from '../../components/admin/reconciliation/ReconciliationCaseList';
import { ReconciliationCaseDetail, type ReconciliationCaseDetailResponse } from '../../components/admin/reconciliation/ReconciliationCaseDetail';

const STATUS_OPTIONS = [
  { value: '', label: 'Все статусы' },
  { value: 'OPEN', label: 'Открытые' },
  { value: 'RESOLVED', label: 'Закрытые' },
];

const TYPE_OPTIONS = [
  { value: '', label: 'Все типы' },
  { value: 'MISMATCH', label: 'Несовпадение суммы' },
  { value: 'DUPLICATE', label: 'Дублирующий платёж' },
  { value: 'STUCK', label: 'Зависший платёж' },
  { value: 'LATE_AFTER_EXPIRY', label: 'Успех после истечения срока' },
];

/**
 * Phase 62: dedicated admin reconciliation-case page (own file/route, mirrors AdminIdentityPage's
 * shape - list-first, not the per-auction-detail-view shape AdminSalePage uses, since
 * reconciliation cases are payment-scoped and browsed as a queue rather than reached via a known
 * route param). Owns the single authoritative GET /admin/reconciliation/cases fetch/refresh
 * cycle - ReconciliationCaseList is pure display, ReconciliationCaseDetail owns the one resolve
 * mutation but always defers back here via onActioned rather than assuming its own action
 * succeeded.
 */
export function AdminReconciliationPage() {
  const [items, setItems] = useState<ReconciliationCaseListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ReconciliationCaseDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const refresh = useCallback(() => {
    setLoading(true);
    const params: Record<string, string> = {};
    if (statusFilter) params.status = statusFilter;
    if (typeFilter) params.type = typeFilter;
    api.get<ReconciliationCaseListItem[]>('/admin/reconciliation/cases', { params })
      .then(r => setItems(r.data))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  }, [statusFilter, typeFilter]);

  useEffect(() => { refresh(); }, [refresh]);

  const loadDetail = (id: number) => {
    setSelectedId(id);
    setDetailLoading(true);
    api.get<ReconciliationCaseDetailResponse>(`/admin/reconciliation/cases/${id}`)
      .then(r => setDetail(r.data))
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false));
  };

  // After any action (success OR 409/404), the acted-on case's state may have changed and the
  // list is stale - always re-fetch the authoritative list and drop the (now possibly stale)
  // selection rather than assume the action succeeded or that the case still belongs in view.
  const handleActioned = () => {
    refresh();
    setSelectedId(null);
    setDetail(null);
  };

  return (
    <div className="max-w-6xl mx-auto py-8 px-4">
      <div className="mb-5">
        <h2 className="text-xl font-bold text-gray-900">Сверка платежей</h2>
        <p className="text-sm text-gray-500 mt-0.5">Дела сверки платежей (несовпадения, дубли, зависшие платежи)</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-[320px_1fr] gap-5">
        <div className="space-y-3">
          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-3 flex gap-2">
            <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}
              className="flex-1 border border-gray-200 rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-blue-500 focus:outline-none">
              {STATUS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)}
              className="flex-1 border border-gray-200 rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-blue-500 focus:outline-none">
              {TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>

          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
            <ReconciliationCaseList
              items={items}
              loading={loading}
              selectedId={selectedId}
              onSelect={item => loadDetail(item.id)}
            />
          </div>
        </div>

        <div>
          {detailLoading ? (
            <div className="p-8 text-center text-gray-400 bg-white border border-gray-100 rounded-2xl">
              Загрузка дела...
            </div>
          ) : detail ? (
            <ReconciliationCaseDetail detail={detail} onActioned={handleActioned} />
          ) : (
            <div className="p-8 text-center text-gray-400 bg-white border border-gray-100 rounded-2xl">
              Выберите дело из списка слева
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
