import { formatDateTime } from '../../../utils/formatDate';
import type { IdentityStatusResponse } from '../../../api/identityApi';

const DOCUMENT_TYPE_LABEL: Record<string, string> = {
  NATIONAL_ID: 'ID-карта',
  INTERNATIONAL_PASSPORT: 'Загранпаспорт',
};

interface Props {
  items: IdentityStatusResponse[];
  loading: boolean;
  selectedId: number | null;
  onSelect: (item: IdentityStatusResponse) => void;
}

/** Phase 57: read-only list from GET /admin/identity/pending - selection only, no mutation here. */
export function IdentityReviewQueue({ items, loading, selectedId, onSelect }: Props) {
  if (loading) {
    return <p className="text-sm text-gray-400 p-4">Загрузка очереди...</p>;
  }

  if (items.length === 0) {
    return <p className="text-sm text-gray-400 p-4">Нет заявок на рассмотрении.</p>;
  }

  return (
    <div className="divide-y divide-gray-100">
      {items.map(item => (
        <button key={item.id} onClick={() => onSelect(item)}
          className={`w-full text-left px-4 py-3 text-sm hover:bg-gray-50 transition-colors ${
            selectedId === item.id ? 'bg-blue-50' : ''
          }`}>
          <p className="font-medium text-gray-800">Пользователь #{item.userId}</p>
          <p className="text-xs text-gray-500">
            {DOCUMENT_TYPE_LABEL[item.documentType] ?? item.documentType} · {item.canonicalDocumentNumber}
          </p>
          <p className="text-xs text-gray-400">{formatDateTime(item.createdAt)}</p>
        </button>
      ))}
    </div>
  );
}
