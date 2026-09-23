import { useCallback, useEffect, useState } from 'react';
import { getById, getPending, type IdentityStatusResponse } from '../../api/identityApi';
import { IdentityReviewQueue } from '../../components/admin/identity/IdentityReviewQueue';
import { IdentityReviewPanel } from '../../components/admin/identity/IdentityReviewPanel';

/**
 * Phase 57: dedicated admin identity-review page (own file/route, NOT added inline to
 * AdminDashboard.tsx, which is already ~2600+ lines). Owns the single authoritative
 * GET /admin/identity/pending fetch/refresh cycle - IdentityReviewQueue is pure display,
 * IdentityReviewPanel owns action side-effects but always defers back here via onActioned rather
 * than assuming its own action succeeded.
 *
 * The pending queue only ever contains PENDING_REVIEW items, so it can never surface a VERIFIED
 * one - and revoke() is only reachable from VERIFIED (see IdentityReviewPanel's own doc comment).
 * The by-ID lookup below uses the existing GET /admin/identity/{id} endpoint to reach a specific
 * (e.g. already-verified) submission directly, so revoke stays reachable through this UI without
 * inventing a second queue or a new backend endpoint.
 */
export function AdminIdentityPage() {
  const [items, setItems] = useState<IdentityStatusResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<IdentityStatusResponse | null>(null);

  const [lookupId, setLookupId] = useState('');
  const [lookupLoading, setLookupLoading] = useState(false);
  const [lookupError, setLookupError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    setLoading(true);
    getPending()
      .then(setItems)
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  // After any action, the acted-on item's status has changed and the pending list is stale -
  // always re-fetch the authoritative list and drop the (now possibly stale) selection rather
  // than assume the action succeeded or that the item still belongs in this queue.
  const handleActioned = () => {
    refresh();
    setSelected(null);
  };

  const handleLookup = () => {
    const id = Number(lookupId);
    if (!Number.isFinite(id) || id <= 0) {
      setLookupError('Введите корректный числовой ID заявки');
      return;
    }
    setLookupError(null);
    setLookupLoading(true);
    getById(id)
      .then(item => setSelected(item))
      .catch((e: any) => setLookupError(e.response?.status === 404 ? 'Заявка не найдена' : 'Не удалось загрузить заявку'))
      .finally(() => setLookupLoading(false));
  };

  return (
    <div className="max-w-6xl mx-auto py-8 px-4">
      <div className="mb-5">
        <h2 className="text-xl font-bold text-gray-900">Подтверждение личности</h2>
        <p className="text-sm text-gray-500 mt-0.5">Заявки пользователей на подтверждение личности</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-[320px_1fr] gap-5">
        <div className="space-y-3">
          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-3 space-y-2">
            <p className="text-xs text-gray-500">Найти заявку по ID (например, для отзыва подтверждённой личности)</p>
            <div className="flex gap-2">
              <input type="text" value={lookupId} onChange={e => setLookupId(e.target.value)}
                placeholder="ID заявки"
                className="flex-1 border border-gray-200 rounded-lg px-2.5 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              <button onClick={handleLookup} disabled={lookupLoading || !lookupId.trim()}
                className="px-3 py-1.5 rounded-lg border text-sm text-gray-600 hover:bg-gray-50 disabled:opacity-50">
                {lookupLoading ? '...' : 'Найти'}
              </button>
            </div>
            {lookupError && <p className="text-xs text-red-600">{lookupError}</p>}
          </div>

          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
            <IdentityReviewQueue
              items={items}
              loading={loading}
              selectedId={selected?.id ?? null}
              onSelect={setSelected}
            />
          </div>
        </div>

        <div>
          {selected ? (
            <IdentityReviewPanel item={selected} onActioned={handleActioned} />
          ) : (
            <div className="p-8 text-center text-gray-400 bg-white border border-gray-100 rounded-2xl">
              Выберите заявку из списка слева или найдите по ID
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
