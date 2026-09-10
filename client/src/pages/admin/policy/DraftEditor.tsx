import { useEffect, useMemo, useState } from 'react';
import api from '../../../api/client';

type PenaltyType = 'PERCENTAGE' | 'FIXED_AMOUNT';

interface ViolationView {
  violationDefinitionId: number;
  code: string | null;
  penaltyType: PenaltyType;
  penaltyValue: number;
}

export interface PolicyDetail {
  id: number;
  versionNumber: number;
  commissionRate: number;
  published: boolean;
  effectiveFrom: string;
  createdBy: number;
  createdAt: string;
  violations: ViolationView[];
}

interface ViolationDefinition {
  id: number;
  code: string;
  description: string;
}

interface Props {
  draft: PolicyDetail;
  catalogue: ViolationDefinition[];
  currentPublishedVersionNumber: number | null;
  onChanged: (updated: PolicyDetail) => void;
  onPublished: () => void;
  onClose: () => void;
}

function apiError(e: any): string {
  return e?.response?.data?.message
    || (e?.response?.data?.details && JSON.stringify(e.response.data.details))
    || 'Ошибка запроса';
}

/**
 * Slice 1 (Policy Administration): edits a single unpublished draft PolicyVersion — commission
 * rate and one penalty row per stable violation definition — then publishes it. The backend
 * remains authoritative for every rule; this component only mirrors the bounds for immediate
 * feedback and gates the Publish button on completeness it can see.
 */
export function DraftEditor({
  draft,
  catalogue,
  currentPublishedVersionNumber,
  onChanged,
  onPublished,
  onClose,
}: Props) {
  const [commission, setCommission] = useState(String(draft.commissionRate));
  const [rows, setRows] = useState<Record<number, { penaltyType: PenaltyType; penaltyValue: string }>>({});
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setCommission(String(draft.commissionRate));
    const next: Record<number, { penaltyType: PenaltyType; penaltyValue: string }> = {};
    for (const v of draft.violations) {
      next[v.violationDefinitionId] = { penaltyType: v.penaltyType, penaltyValue: String(v.penaltyValue) };
    }
    setRows(next);
  }, [draft]);

  const configuredCount = useMemo(
    () => catalogue.filter((d) => rows[d.id] !== undefined).length,
    [catalogue, rows],
  );
  const complete = configuredCount === catalogue.length && catalogue.length > 0;

  const commissionValid = (() => {
    const n = Number(commission);
    return commission.trim() !== '' && !Number.isNaN(n) && n >= 0 && n < 1;
  })();

  // A draft carries NO copy-forward base reference (Slice 1 scope: no base_policy_version_id
  // column, deliberately). The client therefore cannot know this draft's exact base. What it
  // CAN know reliably: version numbers are assigned monotonically at creation, so if a
  // published version exists with a higher number than this draft's, that version was
  // published AFTER this draft was created — meaning publishing this draft will not carry
  // forward anything from versions published in the meantime. Surface that only as a "may be
  // outdated" warning, never as a claim about a specific base version.
  const showStaleWarning =
    currentPublishedVersionNumber !== null && currentPublishedVersionNumber > draft.versionNumber;

  const notify = (text: string, isErr = false) => {
    setMsg(text);
    setErr(isErr);
    setTimeout(() => setMsg(''), 4000);
  };

  const saveCommission = async () => {
    if (!commissionValid) {
      notify('Комиссия должна быть в диапазоне [0, 1)', true);
      return;
    }
    setBusy(true);
    try {
      const r = await api.patch(`/admin/policy/drafts/${draft.id}`, { commissionRate: Number(commission) });
      onChanged(r.data);
      notify('Комиссия сохранена');
    } catch (e) {
      notify(apiError(e), true);
    } finally {
      setBusy(false);
    }
  };

  const saveViolation = async (defId: number) => {
    const row = rows[defId];
    if (!row) return;
    const n = Number(row.penaltyValue);
    if (Number.isNaN(n) || n < 0 || (row.penaltyType === 'PERCENTAGE' && n > 100)) {
      notify('Недопустимое значение штрафа', true);
      return;
    }
    setBusy(true);
    try {
      const r = await api.put(`/admin/policy/drafts/${draft.id}/violations/${defId}`, {
        penaltyType: row.penaltyType,
        penaltyValue: n,
      });
      onChanged(r.data);
      notify('Нарушение сохранено');
    } catch (e) {
      notify(apiError(e), true);
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    setBusy(true);
    try {
      await api.post(`/admin/policy/drafts/${draft.id}/publish`);
      notify('Версия опубликована');
      onPublished();
    } catch (e) {
      notify(apiError(e), true);
    } finally {
      setBusy(false);
    }
  };

  const setRow = (defId: number, patch: Partial<{ penaltyType: PenaltyType; penaltyValue: string }>) =>
    setRows((prev) => ({
      ...prev,
      [defId]: { penaltyType: prev[defId]?.penaltyType ?? 'PERCENTAGE', penaltyValue: prev[defId]?.penaltyValue ?? '', ...patch },
    }));

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-gray-900">Черновик v{draft.versionNumber}</h2>
            <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800">
              ЧЕРНОВИК · РЕДАКТИРУЕМЫЙ
            </span>
          </div>
          <p className="text-sm text-gray-500 mt-0.5">
            Настроено нарушений: {configuredCount} / {catalogue.length}
          </p>
        </div>
        <button onClick={onClose} className="text-sm text-gray-500 hover:text-gray-800">
          ← К списку версий
        </button>
      </div>

      {showStaleWarning && (
        <div className="bg-amber-50 border border-amber-200 text-amber-800 text-sm rounded-xl px-4 py-3">
          Этот черновик мог устареть: после его создания уже была опубликована версия
          v{currentPublishedVersionNumber}. Черновик создавался на основе версии, актуальной на тот
          момент; его публикация НЕ перенесёт изменения из версий, опубликованных позже. Публикация
          всё равно разрешена и создаст новую неизменяемую версию.
        </div>
      )}

      {msg && (
        <div className={`text-sm rounded-xl px-4 py-2 ${err ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700'}`}>
          {msg}
        </div>
      )}

      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm p-5 space-y-2">
        <label className="text-sm font-semibold text-gray-800 block">Ставка комиссии (доля, 0 ≤ x &lt; 1)</label>
        <p className="text-xs text-gray-400">Например, 0.02 = 2%. Берётся с выигрышной суммы при успешной продаже.</p>
        <div className="flex gap-3 items-center">
          <input
            type="number"
            step="0.0001"
            min={0}
            max={0.9999}
            value={commission}
            onChange={(e) => setCommission(e.target.value)}
            className={`w-44 border rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${
              commissionValid ? 'border-gray-200' : 'border-red-400'
            }`}
          />
          <button
            onClick={saveCommission}
            disabled={busy || !commissionValid}
            className="bg-blue-600 text-white px-4 py-2 rounded-xl text-sm font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            Сохранить комиссию
          </button>
        </div>
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm divide-y divide-gray-50">
        {catalogue.map((d) => {
          const row = rows[d.id];
          const configured = row !== undefined;
          return (
            <div key={d.id} className="p-5">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-sm font-semibold text-gray-800">{d.code}</span>
                {!configured && (
                  <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-700">не настроено</span>
                )}
              </div>
              <p className="text-xs text-gray-400 mb-3">{d.description}</p>
              <div className="flex gap-3 items-center">
                <select
                  value={row?.penaltyType ?? 'PERCENTAGE'}
                  onChange={(e) => setRow(d.id, { penaltyType: e.target.value as PenaltyType })}
                  className="border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                >
                  <option value="PERCENTAGE">Процент (0–100)</option>
                  <option value="FIXED_AMOUNT">Фиксированная сумма (≥ 0)</option>
                </select>
                <input
                  type="number"
                  step="0.01"
                  min={0}
                  value={row?.penaltyValue ?? ''}
                  onChange={(e) => setRow(d.id, { penaltyValue: e.target.value })}
                  className="w-40 border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
                <button
                  onClick={() => saveViolation(d.id)}
                  disabled={busy}
                  className="bg-gray-800 text-white px-3 py-2 rounded-xl text-sm font-medium hover:bg-gray-900 disabled:opacity-50"
                >
                  Сохранить
                </button>
              </div>
            </div>
          );
        })}
      </div>

      <div className="flex items-center gap-3">
        <button
          onClick={publish}
          disabled={busy || !complete}
          className="bg-green-600 text-white px-6 py-2.5 rounded-xl font-medium hover:bg-green-700 disabled:opacity-50"
        >
          Опубликовать версию
        </button>
        {!complete && (
          <span className="text-xs text-gray-400">
            Опубликовать можно только полностью настроенный черновик ({configuredCount}/{catalogue.length}).
          </span>
        )}
      </div>
    </div>
  );
}
