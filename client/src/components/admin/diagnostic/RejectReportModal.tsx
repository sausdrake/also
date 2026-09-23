import { useState } from 'react';

type RejectionCategory = 'VEHICLE_CONDITION' | 'SELLER_VIOLATION';

interface Props {
  requestId: number;
  onConfirm: (reason: string, category: RejectionCategory) => void;
  onCancel: () => void;
}

/** The only two categories the backend accepts - never invent a third. */
const CATEGORIES: { value: RejectionCategory; label: string }[] = [
  { value: 'VEHICLE_CONDITION', label: 'Состояние автомобиля не соответствует требованиям' },
  { value: 'SELLER_VIOLATION', label: 'Нарушение продавцом правил платформы' },
];

/**
 * Fixes a pre-existing bug discovered during Phase 55 planning: the previous reject flow
 * (InlinePrompt in AdminDashboard.tsx) called POST .../reject with only `reason`, never the
 * `category` query param that AdminDiagnosticController.rejectReport declares as a required
 * @RequestParam DiagnosticRejectionCategory with no default - meaning report rejection was
 * broken entirely through the UI, for both categories, not just SELLER_VIOLATION. This modal
 * collects both required fields using only the two backend-supported category values.
 *
 * Deliberately its own component, not a modification of the shared InlinePrompt (used
 * elsewhere in AdminDashboard.tsx for unrelated single-field prompts) - avoids affecting any
 * other InlinePrompt call site.
 */
export function RejectReportModal({ requestId, onConfirm, onCancel }: Props) {
  const [reason, setReason] = useState('');
  const [category, setCategory] = useState<RejectionCategory | ''>('');

  const canConfirm = reason.trim().length > 0 && category !== '';

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg max-h-[85vh] overflow-y-auto">
        <div className="p-6 space-y-4">
          <h3 className="font-semibold text-gray-900">Причина отклонения отчёта #{requestId}</h3>

          <div className="space-y-1.5">
            <p className="text-xs text-gray-500">Категория отклонения</p>
            <select value={category} onChange={e => setCategory(e.target.value as RejectionCategory | '')}
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none">
              <option value="">Выберите категорию...</option>
              {CATEGORIES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
            </select>
          </div>

          <textarea autoFocus value={reason} onChange={e => setReason(e.target.value)}
            placeholder="Опишите, что нужно исправить диагносту..." rows={4} maxLength={500}
            className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none" />
          <p className="text-xs text-gray-400 text-right">{reason.length}/500</p>

          <div className="flex gap-2 justify-end">
            <button onClick={onCancel} className="px-4 py-2 border border-gray-200 rounded-lg text-sm hover:bg-gray-50">
              Отмена
            </button>
            <button
              onClick={() => canConfirm && onConfirm(reason.trim(), category as RejectionCategory)}
              disabled={!canConfirm}
              className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700 disabled:opacity-50">
              Подтвердить
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
