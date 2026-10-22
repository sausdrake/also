import { useCallback, useEffect, useState } from 'react';
import api from '../../api/client';
import { violationLabel } from '../../domain/violation';
import { formatDateTime } from '../../utils/formatDate';
import { DraftEditor, type PolicyDetail } from './policy/DraftEditor';

interface PolicySummary {
  id: number;
  versionNumber: number;
  commissionRate: number;
  published: boolean;
  createdAt: string;
}

interface ViolationDefinition {
  id: number;
  code: string;
  description: string;
}

function apiError(e: any): string {
  return e?.response?.data?.message || 'Ошибка запроса';
}

const pct = (rate: number) => `${(rate * 100).toFixed(2)}%`;

/**
 * Slice 1 (Policy Administration): the admin surface for the immutable pricing/violation
 * PolicyVersion. Distinguishes PUBLISHED / IMMUTABLE from DRAFT / EDITABLE; drives the
 * create-draft → edit → publish flow. All rules are enforced by the backend; this page is a
 * thin client of /api/v1/admin/policy.
 */
export function PolicyPage() {
  const [current, setCurrent] = useState<PolicyDetail | null>(null);
  const [history, setHistory] = useState<PolicySummary[]>([]);
  const [catalogue, setCatalogue] = useState<ViolationDefinition[]>([]);
  const [openDraft, setOpenDraft] = useState<PolicyDetail | null>(null);
  const [readOnly, setReadOnly] = useState<PolicyDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState(false);

  const notify = (text: string, isErr = false) => {
    setMsg(text);
    setErr(isErr);
    setTimeout(() => setMsg(''), 4000);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [cur, hist, cat] = await Promise.allSettled([
        api.get('/admin/policy/current'),
        api.get('/admin/policy'),
        api.get('/admin/policy/violation-definitions'),
      ]);
      setCurrent(cur.status === 'fulfilled' ? cur.value.data : null);
      setHistory(hist.status === 'fulfilled' ? hist.value.data : []);
      setCatalogue(cat.status === 'fulfilled' ? cat.value.data : []);
    } catch {
      notify('Ошибка загрузки', true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const createDraft = async () => {
    try {
      const r = await api.post('/admin/policy/drafts', {
        commissionRate: current ? current.commissionRate : 0.02,
      });
      setReadOnly(null);
      setOpenDraft(r.data);
      await refreshHistory();
    } catch (e) {
      notify(apiError(e), true);
    }
  };

  const refreshHistory = async () => {
    try {
      const r = await api.get('/admin/policy');
      setHistory(r.data);
    } catch {
      /* non-fatal */
    }
  };

  const openVersion = async (id: number) => {
    try {
      const r = await api.get<PolicyDetail>(`/admin/policy/${id}`);
      if (r.data.published) {
        setOpenDraft(null);
        setReadOnly(r.data);
      } else {
        setReadOnly(null);
        setOpenDraft(r.data);
      }
    } catch (e) {
      notify(apiError(e), true);
    }
  };

  if (openDraft) {
    return (
      <>
        {msg && (
          <div className={`text-sm rounded-xl px-4 py-2 mb-3 ${err ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700'}`}>
            {msg}
          </div>
        )}
        <DraftEditor
          draft={openDraft}
          catalogue={catalogue}
          currentPublishedVersionNumber={current?.versionNumber ?? null}
          currentPublishedId={current?.id ?? null}
          onChanged={(u) => setOpenDraft(u)}
          onPublished={async () => {
            setOpenDraft(null);
            await load();
            notify('Версия опубликована');
          }}
          onClose={() => setOpenDraft(null)}
        />
      </>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Ценовая политика</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            Комиссия и штрафы за нарушения продавца. Опубликованные версии неизменяемы.
          </p>
        </div>
        <button
          onClick={createDraft}
          className="bg-blue-600 text-white px-5 py-2 rounded-xl text-sm font-medium hover:bg-blue-700"
        >
          + Создать черновик
        </button>
      </div>

      {msg && (
        <div className={`text-sm rounded-xl px-4 py-2 ${err ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700'}`}>
          {msg}
        </div>
      )}

      {loading ? (
        <div className="p-8 text-center text-gray-400">Загрузка...</div>
      ) : (
        <>
          {/* Current published */}
          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5">
            <div className="flex items-center gap-2 mb-3">
              <h3 className="text-sm font-bold text-gray-900">Текущая опубликованная политика</h3>
              {current && (
                <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-green-100 text-green-800">
                  ОПУБЛИКОВАНА · НЕИЗМЕНЯЕМА
                </span>
              )}
            </div>
            {!current ? (
              <p className="text-sm text-gray-400">Нет опубликованной версии.</p>
            ) : (
              <div className="text-sm text-gray-700 space-y-2">
                <div>
                  Версия <b>v{current.versionNumber}</b> · комиссия <b>{pct(current.commissionRate)}</b> · опубликована{' '}
                  {formatDateTime(current.createdAt)}
                </div>
                <div className="border border-gray-100 rounded-xl divide-y divide-gray-50">
                  {current.violations.map((v) => (
                    <div key={v.violationDefinitionId} className="px-4 py-2 flex justify-between text-xs">
                      <span className="text-gray-600 min-w-0 break-words pr-3">
                        {violationLabel(v.code) ?? v.code ?? `#${v.violationDefinitionId}`}
                        {violationLabel(v.code) && <span className="block font-mono text-[10px] text-gray-400 break-all">{v.code}</span>}
                      </span>
                      <span className="text-gray-900 font-medium shrink-0">
                        {v.penaltyType === 'PERCENTAGE' ? `${v.penaltyValue}%` : `${v.penaltyValue}`}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* History */}
          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm">
            <div className="px-5 py-3 border-b border-gray-50">
              <h3 className="text-sm font-bold text-gray-900">История версий</h3>
            </div>
            <div className="divide-y divide-gray-50">
              {history.length === 0 && <div className="p-5 text-sm text-gray-400">Версий пока нет.</div>}
              {history.map((h) => (
                <button
                  key={h.id}
                  onClick={() => openVersion(h.id)}
                  className="w-full text-left px-5 py-3 hover:bg-gray-50 flex items-center justify-between"
                >
                  <span className="text-sm text-gray-800">
                    v{h.versionNumber} · комиссия {pct(h.commissionRate)}
                    <span className="text-gray-400"> · {formatDateTime(h.createdAt)}</span>
                  </span>
                  <span
                    className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                      h.published ? 'bg-green-100 text-green-800' : 'bg-amber-100 text-amber-800'
                    }`}
                  >
                    {h.published ? 'опубликована' : 'черновик'}
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* Read-only published detail */}
          {readOnly && (
            <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5">
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-2">
                  <h3 className="text-sm font-bold text-gray-900">Версия v{readOnly.versionNumber}</h3>
                  <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-green-100 text-green-800">
                    ОПУБЛИКОВАНА · НЕИЗМЕНЯЕМА
                  </span>
                </div>
                <button onClick={() => setReadOnly(null)} className="text-sm text-gray-500 hover:text-gray-800">
                  Закрыть
                </button>
              </div>
              <div className="text-sm text-gray-700">
                Комиссия <b>{pct(readOnly.commissionRate)}</b>
              </div>
              <div className="mt-2 border border-gray-100 rounded-xl divide-y divide-gray-50">
                {readOnly.violations.map((v) => (
                  <div key={v.violationDefinitionId} className="px-4 py-2 flex justify-between text-xs">
                    <span className="text-gray-600 min-w-0 break-words pr-3">
                      {violationLabel(v.code) ?? v.code ?? `#${v.violationDefinitionId}`}
                      {violationLabel(v.code) && <span className="block font-mono text-[10px] text-gray-400 break-all">{v.code}</span>}
                    </span>
                    <span className="text-gray-900 font-medium shrink-0">
                      {v.penaltyType === 'PERCENTAGE' ? `${v.penaltyValue}%` : `${v.penaltyValue}`}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
