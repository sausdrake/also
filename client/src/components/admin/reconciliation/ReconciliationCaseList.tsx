import { formatDateTime } from '../../../utils/formatDate';

export interface ReconciliationCaseListItem {
  id: number;
  paymentId: number;
  paymentExists: boolean;
  type: string;
  status: string;
  openedAt: string;
  resolvedAt: string | null;
  resolvedByAdminId: number | null;
  requestedAmount: number | null;
  requestedCurrency: string | null;
}

const TYPE_LABEL: Record<string, string> = {
  MISMATCH: 'Несовпадение суммы',
  DUPLICATE: 'Дублирующий платёж',
  STUCK: 'Зависший платёж',
  LATE_AFTER_EXPIRY: 'Успех после истечения срока',
};

const STATUS_LABEL: Record<string, string> = {
  OPEN: 'Открыто',
  RESOLVED: 'Закрыто',
};

const STATUS_COLOR: Record<string, string> = {
  OPEN: 'bg-amber-50 text-amber-700',
  RESOLVED: 'bg-gray-100 text-gray-600',
};

interface Props {
  items: ReconciliationCaseListItem[];
  loading: boolean;
  selectedId: number | null;
  onSelect: (item: ReconciliationCaseListItem) => void;
}

/** Phase 62: read-only list from GET /admin/reconciliation/cases - selection only, no mutation here. */
export function ReconciliationCaseList({ items, loading, selectedId, onSelect }: Props) {
  if (loading) {
    return <p className="text-sm text-gray-400 p-4">Загрузка дел сверки...</p>;
  }

  if (items.length === 0) {
    return <p className="text-sm text-gray-400 p-4">Дел сверки не найдено.</p>;
  }

  return (
    <div className="divide-y divide-gray-100">
      {items.map(item => (
        <button key={item.id} onClick={() => onSelect(item)}
          className={`w-full text-left px-4 py-3 text-sm hover:bg-gray-50 transition-colors ${
            selectedId === item.id ? 'bg-blue-50' : ''
          }`}>
          <div className="flex items-center justify-between gap-2">
            <p className="font-medium text-gray-800">{TYPE_LABEL[item.type] ?? item.type}</p>
            <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${STATUS_COLOR[item.status] ?? 'bg-gray-50 text-gray-600'}`}>
              {STATUS_LABEL[item.status] ?? item.status}
            </span>
          </div>
          <p className="text-xs text-gray-500">
            {item.paymentExists ? `Платёж #${item.paymentId}` : `Платёж #${item.paymentId} (не найден)`}
            {item.requestedAmount != null && ` · ${item.requestedAmount.toLocaleString('ru')} ${item.requestedCurrency ?? ''}`}
          </p>
          <p className="text-xs text-gray-400">{formatDateTime(item.openedAt)}</p>
        </button>
      ))}
    </div>
  );
}
