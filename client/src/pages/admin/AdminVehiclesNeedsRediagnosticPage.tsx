import { useEffect, useState } from 'react';
import api from '../../api/client';
import { formatDateTime } from '../../utils/formatDate';

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

/**
 * Phase 84: read-only admin visibility for vehicles in VehicleStatus.NEEDS_REDIAGNOSTIC -
 * reached whenever an admin resolves a failed sale as PLATFORM_FAULT or as a seller-violation
 * (Outcome C), see SaleFailureResolutionOrchestrator. This page is deliberately informational
 * only: no re-diagnostic request creation, no reassignment, no re-auction, no status transition
 * of any kind exists here or anywhere else in the codebase yet - that workflow is a separate,
 * not-yet-decided business decision. Do not add an action to this page without that decision
 * being made first.
 */
export function AdminVehiclesNeedsRediagnosticPage() {
  const [vehicles, setVehicles] = useState<VehicleResponse[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get<VehicleResponse[]>('/admin/vehicles/needs-rediagnostic')
      .then(r => setVehicles(r.data))
      .catch(() => setVehicles([]))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="max-w-4xl mx-auto py-8 px-4 space-y-5">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Требуют повторной диагностики</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Автомобили, для которых продажа завершилась исходом «Ошибка платформы» или «Нарушение
          продавца» — их статус требует новой диагностики. Это информационный список: действие по
          повторной диагностике в системе пока не реализовано.
        </p>
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
        {loading ? (
          <p className="text-sm text-gray-400 p-4">Загрузка...</p>
        ) : vehicles.length === 0 ? (
          <p className="text-sm text-gray-400 p-4">Нет автомобилей, требующих повторной диагностики.</p>
        ) : (
          <div className="divide-y divide-gray-100">
            {vehicles.map(v => (
              <div key={v.id} className="px-4 py-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <p className="font-medium text-gray-800">{v.brand} {v.model} ({v.year})</p>
                  <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-amber-50 text-amber-700">
                    {v.status}
                  </span>
                </div>
                <p className="text-xs text-gray-500">
                  VIN: {v.vin}{v.licensePlate && ` · Госномер: ${v.licensePlate}`}
                </p>
                {v.diagnosticId && (
                  <p className="text-xs text-gray-400">
                    Диагностическая заявка: #{v.diagnosticId}{v.diagnosticName && ` (${v.diagnosticName})`}
                  </p>
                )}
                {v.updatedAt && (
                  <p className="text-xs text-gray-400">Обновлено: {formatDateTime(v.updatedAt)}</p>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
