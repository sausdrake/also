import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { accept, ACCEPTANCE_VERB, LEGAL_SLUGS, requiredAcceptances, type LegalDocument } from '../../api/legal';
import { useAuthStore } from '../../store/authStore';

/**
 * LEGAL-DATA-01: after sign-in (and whenever a new edition comes into force) the user is asked to accept the
 * versions in force they have not accepted yet - e.g. an account created through Telegram, or a changed Terms.
 * Nothing is asked while no document is in force. The server enforces the same rule on protected operations
 * (LEGAL_ACCEPTANCE_REQUIRED); this dialog is the way to satisfy it.
 */
export function LegalAcceptanceGate() {
  const isAuthenticated = useAuthStore(s => s.isAuthenticated);
  const [docs, setDocs] = useState<LegalDocument[]>([]);
  const [checked, setChecked] = useState<Record<number, boolean>>({});
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!isAuthenticated) { setDocs([]); return; }
    requiredAcceptances().then(d => { setDocs(d); setChecked({}); }).catch(() => setDocs([]));
  }, [isAuthenticated]);

  useEffect(load, [load]);

  if (!isAuthenticated || docs.length === 0) return null;

  const allChecked = docs.every(d => checked[d.id]);

  const submit = async () => {
    setSending(true);
    setError(null);
    try {
      await accept(docs.map(d => d.id));
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message || 'Не удалось сохранить. Попробуйте ещё раз.');
    } finally {
      setSending(false);
    }
  };

  return (
    <div role="dialog" aria-modal="true" aria-labelledby="legal-gate-title"
      className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-xl max-w-md w-full p-5 space-y-4">
        <h2 id="legal-gate-title" className="text-lg font-semibold text-gray-900">Документы платформы</h2>
        <p className="text-sm text-gray-600">
          Чтобы продолжить, ознакомьтесь с действующими редакциями документов.
        </p>
        <ul className="space-y-2">
          {docs.map(d => (
            <li key={d.id}>
              <label className="flex items-start gap-2 text-sm text-gray-800">
                <input type="checkbox" className="mt-0.5" checked={!!checked[d.id]}
                  onChange={e => setChecked(c => ({ ...c, [d.id]: e.target.checked }))} />
                <span>
                  {ACCEPTANCE_VERB[d.acceptanceKind]}:{' '}
                  <Link to={`/legal/${LEGAL_SLUGS[d.documentType]}`} target="_blank" className="text-blue-600 underline">
                    {d.title}
                  </Link>{' '}
                  <span className="text-gray-400">(редакция {d.version})</span>
                </span>
              </label>
            </li>
          ))}
        </ul>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button onClick={submit} disabled={!allChecked || sending}
          className="w-full py-2.5 bg-blue-600 text-white rounded-xl font-medium disabled:opacity-50">
          {sending ? 'Сохранение…' : 'Продолжить'}
        </button>
      </div>
    </div>
  );
}
