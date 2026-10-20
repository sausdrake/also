import { useState } from 'react';
import api from '../../api/client';
import { apiErrorMessage } from '../../api/errors';

export interface TemplateOption {
  id: number;
  name: string;
  vehicleType: string;
}

export interface TemplateBindingProps {
  requestId: number;
  templates: TemplateOption[];
  /** Set when the active templates could not be loaded. */
  loadError?: string;
  onBound: () => void;
}

/**
 * FE-DIAG-01: an accepted request has no questionnaire until the diagnostician binds one
 * (POST /diagnostic/work/{id}/template, MEDIA-3.1A). The binding is one-time and irreversible on
 * the backend, so the choice is explicit (select + confirm button) and never made implicitly by
 * a dropdown change. Errors are the backend's own messages (foreign request, inactive template,
 * already bound to another template, request no longer IN_PROGRESS).
 */
export function TemplateBinding({ requestId, templates, loadError, onBound }: TemplateBindingProps) {
  const [selected, setSelected] = useState<number | null>(null);
  const [binding, setBinding] = useState(false);
  const [error, setError] = useState('');

  const bind = async () => {
    if (selected === null || binding) return;
    setBinding(true);
    setError('');
    try {
      await api.post(`/diagnostic/work/${requestId}/template`, { templateId: selected });
      onBound();
    } catch (e) {
      setError(apiErrorMessage(e, 'Не удалось выбрать шаблон'));
    } finally {
      setBinding(false);
    }
  };

  return (
    <div className="space-y-3">
      <div>
        <p className="font-medium text-sm text-gray-900">Выберите шаблон анкеты</p>
        <p className="text-xs text-gray-500 mt-0.5">
          Шаблон выбирается один раз: после подтверждения его нельзя будет сменить.
        </p>
      </div>
      {loadError ? (
        <div className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{loadError}</div>
      ) : templates.length === 0 ? (
        <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
          Нет активных шаблонов анкеты. Обратитесь к администратору.
        </div>
      ) : (
        <div className="flex flex-col sm:flex-row gap-2">
          <select
            aria-label="Шаблон анкеты"
            value={selected ?? ''}
            onChange={e => { setSelected(e.target.value ? Number(e.target.value) : null); setError(''); }}
            disabled={binding}
            className="flex-1 border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none">
            <option value="">— выберите шаблон —</option>
            {templates.map(t => (
              <option key={t.id} value={t.id}>{t.name} ({t.vehicleType})</option>
            ))}
          </select>
          <button type="button" onClick={bind} disabled={selected === null || binding}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50 whitespace-nowrap">
            {binding ? 'Сохранение…' : 'Подтвердить шаблон'}
          </button>
        </div>
      )}
      {error && (
        <div role="alert" className="p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{error}</div>
      )}
    </div>
  );
}
