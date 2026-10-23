import { useEffect, useState } from 'react';
import api from '../../api/client';
import { formatDateTime } from '../../utils/formatDate';
import { VEHICLE_STATUS_LABELS, type VehicleStatus } from '../../domain/vehicle';
import { DIAGNOSTIC_STATUS_LABELS, type DiagnosticRequestStatus } from '../../domain/diagnosticRequest';

interface VehicleResponse {
  id: number;
  brand: string;
  model: string;
  year: number;
  vin: string;
  licensePlate: string | null;
  status: string;
  diagnosticId: number | null;
  diagnosticName: string | null;
  updatedAt: string | null;
}

type RediagnosticReason = 'DIAGNOSTIC_ERROR' | 'SELLER_FAULT';

interface HistoryEntry {
  id: number;
  status: string;
  createdAt: string | null;
  previousDiagnosticRequestId: number | null;
  rediagnosticReason: RediagnosticReason | null;
  rediagnosticComment: string | null;
  rediagnosticOrderedAt: string | null;
}

const REASONS: { value: RediagnosticReason; label: string; hint: string }[] = [
  { value: 'DIAGNOSTIC_ERROR', label: 'Ошибка диагностики', hint: 'Стоимость оплачивает AlSo, продавец не платит и не штрафуется' },
  { value: 'SELLER_FAULT', label: 'Нарушение продавца', hint: 'Стоимость диагностики удерживается с депозита продавца' },
];

const REASON_LABEL: Record<RediagnosticReason, string> = {
  DIAGNOSTIC_ERROR: 'ошибка диагностики',
  SELLER_FAULT: 'нарушение продавца',
};

/**
 * Vehicles in VehicleStatus.NEEDS_REDIAGNOSTIC - reached when a failed sale is resolved as
 * PLATFORM_FAULT or as a seller violation (Outcome C). MVP-005: the admin orders the
 * re-diagnostic here - a new diagnostic request for the same vehicle, with a mandatory reason
 * that must match the sale's recorded fault and decides who pays - and can read the vehicle's
 * whole diagnostic history (original request, reasons, re-diagnostics).
 */
export function AdminVehiclesNeedsRediagnosticPage() {
  const [vehicles, setVehicles] = useState<VehicleResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [ordering, setOrdering] = useState<number | null>(null);
  const [reason, setReason] = useState<RediagnosticReason | ''>('');
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const [historyOf, setHistoryOf] = useState<number | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);

  const load = () => {
    api.get<VehicleResponse[]>('/admin/vehicles/needs-rediagnostic')
      .then(r => setVehicles(r.data))
      .catch(() => setVehicles([]))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const loadHistory = (vehicleId: number) => {
    api.get<HistoryEntry[]>(`/admin/vehicles/${vehicleId}/diagnostic-history`)
      .then(r => setHistory(r.data))
      .catch(() => setHistory([]));
  };

  const startOrder = (vehicleId: number) => {
    setOrdering(ordering === vehicleId ? null : vehicleId);
    setReason('');
    setComment('');
    setMsg(null);
  };

  const submitOrder = async () => {
    if (ordering == null || !reason) return;
    setSubmitting(true);
    try {
      const r = await api.post(`/admin/vehicles/${ordering}/rediagnostic`, {
        reason,
        comment: comment.trim() || null,
      });
      setMsg({ text: `Повторная диагностика назначена: заявка #${r.data.id}`, ok: true });
      if (historyOf === ordering) loadHistory(ordering);
      setOrdering(null);
      load();
    } catch (e: any) {
      setMsg({ text: e.response?.data?.message || 'Не удалось назначить повторную диагностику', ok: false });
    } finally {
      setSubmitting(false);
    }
  };

  const toggleHistory = (vehicleId: number) => {
    if (historyOf === vehicleId) { setHistoryOf(null); return; }
    setHistoryOf(vehicleId);
    setHistory([]);
    loadHistory(vehicleId);
  };

  return (
    <div className="max-w-4xl mx-auto py-8 px-4 space-y-5">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Требуют повторной диагностики</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Автомобили, продажа которых завершилась исходом «Ошибка платформы» или «Нарушение продавца».
          Назначьте повторную диагностику: будет создана новая заявка для того же автомобиля, прежние отчёты сохраняются.
        </p>
      </div>

      {msg && (
        <div className={`text-sm rounded-lg px-3 py-2 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>{msg.text}</div>
      )}

      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
        {loading ? (
          <p className="text-sm text-gray-400 p-4">Загрузка...</p>
        ) : vehicles.length === 0 ? (
          <p className="text-sm text-gray-400 p-4">Нет автомобилей, требующих повторной диагностики.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {vehicles.map(v => (
              <div key={v.id} className="px-4 py-3 text-sm space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium text-gray-800">{v.brand} {v.model} ({v.year})</p>
                  <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-amber-50 text-amber-700">
                    {VEHICLE_STATUS_LABELS[v.status as VehicleStatus] ?? v.status}
                  </span>
                </div>
                <p className="text-xs text-gray-500">
                  VIN: {v.vin}{v.licensePlate && ` · Госномер: ${v.licensePlate}`}
                </p>
                {v.updatedAt && (
                  <p className="text-xs text-gray-400">Обновлено: {formatDateTime(v.updatedAt)}</p>
                )}
                <div className="flex gap-2">
                  <button onClick={() => startOrder(v.id)}
                    className="text-xs px-3 py-1.5 rounded-lg bg-blue-600 text-white hover:bg-blue-700">
                    Назначить повторную диагностику
                  </button>
                  <button onClick={() => toggleHistory(v.id)}
                    className="text-xs px-3 py-1.5 rounded-lg border text-gray-600 hover:bg-gray-50">
                    {historyOf === v.id ? 'Скрыть историю' : 'История диагностик'}
                  </button>
                </div>

                {ordering === v.id && (
                  <div className="bg-gray-50 border border-gray-100 rounded-xl p-3 space-y-2" data-testid={`order-form-${v.id}`}>
                    <p className="text-xs font-semibold text-gray-700">Причина повторной диагностики *</p>
                    {REASONS.map(r => (
                      <label key={r.value} className="flex items-start gap-2 text-sm cursor-pointer">
                        <input type="radio" name={`reason-${v.id}`} checked={reason === r.value}
                          onChange={() => setReason(r.value)} className="mt-1" />
                        <span>
                          <span className="font-medium text-gray-800">{r.label}</span>
                          <span className="block text-xs text-gray-500">{r.hint}</span>
                        </span>
                      </label>
                    ))}
                    <textarea value={comment} onChange={e => setComment(e.target.value)} maxLength={2000} rows={2}
                      aria-label="Комментарий" placeholder="Комментарий (необязательно)"
                      className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                    <div className="flex gap-2">
                      <button onClick={submitOrder} disabled={!reason || submitting}
                        className="text-xs px-3 py-1.5 rounded-lg bg-blue-600 text-white disabled:opacity-50">
                        {submitting ? 'Назначение...' : 'Назначить'}
                      </button>
                      <button onClick={() => setOrdering(null)} disabled={submitting}
                        className="text-xs px-3 py-1.5 rounded-lg border text-gray-600">Отмена</button>
                    </div>
                  </div>
                )}

                {historyOf === v.id && (
                  <ol className="border-l-2 border-gray-200 pl-3 space-y-1" data-testid={`history-${v.id}`}>
                    {history.map(h => (
                      <li key={h.id} className="text-xs text-gray-600">
                        <span className="font-medium">Заявка #{h.id}</span> · {DIAGNOSTIC_STATUS_LABELS.owner[h.status as DiagnosticRequestStatus] ?? h.status}
                        {h.createdAt && ` · ${formatDateTime(h.createdAt)}`}
                        {h.previousDiagnosticRequestId != null && h.rediagnosticReason && (
                          <span className="block text-amber-700">
                            Повторная диагностика заявки #{h.previousDiagnosticRequestId}: {REASON_LABEL[h.rediagnosticReason]}
                            {h.rediagnosticComment && ` — ${h.rediagnosticComment}`}
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
