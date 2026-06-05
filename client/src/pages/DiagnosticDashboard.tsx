import { useState, useEffect, useMemo } from 'react';
import { Routes, Route, NavLink, useNavigate } from 'react-router-dom';
import api from '../api/client';
import { formatDate } from '../utils/formatDate';

// ─── Types ───
interface Request {
  id: number;
  brand: string;
  model: string;
  year: number;
  vin: string;
  status: string;
  createdAt: string;
  assignedTo?: number;
}

interface Item {
  id: number;
  title: string;
  description?: string;
  fieldType: string;
  isRequired: boolean;
  hasRating: boolean;
  hasPhoto: boolean;
}

interface Group {
  id: number;
  name: string;
  sortOrder: number;
  items: Item[];
}

interface Template {
  id: number;
  name: string;
  vehicleType: string;
  groups: Group[];
}

interface Answer {
  itemId: number;
  rating: number | null;
  comment: string;
  photoUrls: string[];
  videoUrls: string[];
  isSkipped: boolean;
}

// Backend COMPLETED = UI SUBMITTED
const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  PENDING:     { label: 'Ожидает',             color: 'bg-yellow-100 text-yellow-700' },
  IN_PROGRESS: { label: 'В работе',            color: 'bg-blue-100 text-blue-700' },
  COMPLETED:   { label: 'Отправлен на проверку', color: 'bg-purple-100 text-purple-700' },
  APPROVED:    { label: 'Одобрен',             color: 'bg-green-100 text-green-700' },
  REJECTED:    { label: 'Отклонён',            color: 'bg-red-100 text-red-700' },
  DRAFT:       { label: 'Черновик',            color: 'bg-gray-100 text-gray-600' },
};

// Can the diagnostic still edit this report?
const isEditable = (status: string) => status === 'IN_PROGRESS' || status === 'REJECTED';

// ─── Field renderer for the report form ───
function FieldInput({ item, ans, onChange, hasError }: {
  item: Item;
  ans: Answer;
  onChange: (field: keyof Answer, value: any) => void;
  hasError: boolean;
}) {
  const ft = item.fieldType || (item.hasRating ? 'NUMBER' : item.hasPhoto ? 'PHOTO' : 'TEXTAREA');
  const options = ft === 'SELECT' && item.description
    ? item.description.split('\n').filter(o => o.trim())
    : [];

  switch (ft) {
    case 'NUMBER':
      return (
        <div>
          <p className="text-xs text-gray-500 mb-1">Оценка (1–5)</p>
          <div className="flex gap-1.5">
            {[1, 2, 3, 4, 5].map(star => (
              <button key={star} type="button"
                onClick={() => onChange('rating', ans.rating === star ? null : star)}
                className={`w-9 h-9 rounded-lg text-sm font-semibold transition-colors ${
                  ans.rating != null && ans.rating >= star
                    ? 'bg-yellow-400 text-white'
                    : 'bg-gray-100 text-gray-500 hover:bg-yellow-100'
                }`}>
                {star}
              </button>
            ))}
            {ans.rating && (
              <span className="self-center text-xs text-gray-400 ml-1">
                {['', 'Плохо', 'Удовлетворительно', 'Нормально', 'Хорошо', 'Отлично'][ans.rating]}
              </span>
            )}
          </div>
        </div>
      );

    case 'CHECKBOX':
      return (
        <label className="flex items-center gap-2 cursor-pointer select-none">
          <input type="checkbox"
            checked={ans.comment === 'true'}
            onChange={e => onChange('comment', e.target.checked ? 'true' : 'false')}
            className="w-4 h-4" />
          <span className="text-sm text-gray-700">{item.title}</span>
        </label>
      );

    case 'SELECT':
      return (
        <div>
          <select
            value={ans.comment || ''}
            onChange={e => onChange('comment', e.target.value)}
            className={`w-full border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}>
            <option value="">— Выберите вариант —</option>
            {options.map(opt => <option key={opt} value={opt}>{opt}</option>)}
          </select>
        </div>
      );

    case 'TEXT':
      return (
        <input
          type="text"
          value={ans.comment}
          onChange={e => onChange('comment', e.target.value)}
          placeholder={item.isRequired ? 'Ответ (обязателен)' : 'Ответ'}
          className={`w-full border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}
        />
      );

    case 'PHOTO':
      return (
        <div>
          <p className="text-xs text-gray-500 mb-1">Ссылка на фото (URL)</p>
          <input
            type="url"
            value={ans.photoUrls?.[0] || ''}
            onChange={e => onChange('photoUrls', e.target.value ? [e.target.value] : [])}
            placeholder="https://..."
            className={`w-full border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}
          />
        </div>
      );

    case 'TEXTAREA':
    default:
      return (
        <textarea
          value={ans.comment}
          onChange={e => onChange('comment', e.target.value)}
          placeholder={item.isRequired ? 'Комментарий (обязателен)' : 'Комментарий'}
          rows={2}
          className={`w-full border rounded-lg px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}
        />
      );
  }
}

// ─── Форма заполнения отчёта ───
function ReportForm({ requestId, onDone }: { requestId: number; onDone: () => void }) {
  const [data, setData] = useState<any>(null);
  const [answers, setAnswers] = useState<Record<number, Answer>>({});
  const [openGroups, setOpenGroups] = useState<Set<number>>(new Set());
  const [msg, setMsg] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [validationErrors, setValidationErrors] = useState<Set<number>>(new Set());

  useEffect(() => {
    api.get(`/diagnostic/work/${requestId}`).then(r => {
      setData(r.data);
      const groups: Group[] = r.data?.template?.groups ?? [];
      setOpenGroups(new Set(groups.map((g: Group) => g.id)));
      const initial: Record<number, Answer> = {};
      (r.data.existingAnswers || []).forEach((a: any) => {
        initial[a.inspectionItemId] = {
          itemId: a.inspectionItemId,
          rating: a.rating,
          comment: a.comment || '',
          photoUrls: a.photoUrls || [],
          videoUrls: a.videoUrls || [],
          isSkipped: false,
        };
      });
      setAnswers(initial);
    }).catch(() => setMsg('Не удалось загрузить заявку'));
  }, [requestId]);

  const setAnswer = (itemId: number, field: keyof Answer, value: any) => {
    setAnswers(prev => ({
      ...prev,
      [itemId]: {
        ...(prev[itemId] || { itemId, rating: null, comment: '', photoUrls: [], videoUrls: [], isSkipped: false }),
        [field]: value,
      },
    }));
    setValidationErrors(prev => { const s = new Set(prev); s.delete(itemId); return s; });
  };

  const toggleGroup = (id: number) => setOpenGroups(prev => {
    const s = new Set(prev);
    s.has(id) ? s.delete(id) : s.add(id);
    return s;
  });

  const validateForSubmit = (): boolean => {
    const template: Template = data?.template;
    if (!template) return true;
    const errors = new Set<number>();
    for (const group of template.groups) {
      for (const item of group.items) {
        if (item.isRequired) {
          const ans = answers[item.id];
          const ft = item.fieldType || 'TEXTAREA';
          let filled = false;
          if (ft === 'NUMBER') filled = ans?.rating != null && ans.rating > 0;
          else if (ft === 'PHOTO') filled = (ans?.photoUrls?.length ?? 0) > 0;
          else if (ft === 'CHECKBOX') filled = ans?.comment === 'true' || ans?.comment === 'false';
          else filled = ans?.comment?.trim().length > 0;
          if (!filled) errors.add(item.id);
        }
      }
    }
    setValidationErrors(errors);
    if (errors.size > 0) {
      const errGroups = new Set<number>();
      for (const group of template.groups) {
        if (group.items.some(i => errors.has(i.id))) errGroups.add(group.id);
      }
      setOpenGroups(prev => new Set([...prev, ...errGroups]));
    }
    return errors.size === 0;
  };

  const submit = async (isSubmit: boolean) => {
    if (isSubmit && !validateForSubmit()) {
      setMsg('Заполните все обязательные пункты (отмечены *)');
      return;
    }
    setSubmitting(true);
    try {
      const answerList = Object.values(answers);
      await api.post(`/diagnostic/work/${requestId}/answers`, { answers: answerList, submit: isSubmit });
      setMsg(isSubmit ? 'Отчёт отправлен на проверку!' : 'Черновик сохранён');
      if (isSubmit) setTimeout(onDone, 1500);
    } catch (e: any) {
      setMsg(e.response?.data?.message || 'Ошибка сохранения');
    } finally {
      setSubmitting(false);
    }
  };

  if (!data) return (
    <div className="flex items-center justify-center py-16">
      {msg ? <p className="text-red-500">{msg}</p> : <p className="text-gray-400">Загрузка отчёта...</p>}
    </div>
  );

  const template: Template = data.template;
  const groups: Group[] = template?.groups ?? [];
  const status: string = data.status || 'IN_PROGRESS';
  const editable = isEditable(status);

  const totalRequired = groups.flatMap(g => g.items).filter(i => i.isRequired).length;
  const filledRequired = groups.flatMap(g => g.items).filter(i => {
    if (!i.isRequired) return false;
    const ans = answers[i.id];
    const ft = i.fieldType || 'TEXTAREA';
    if (ft === 'NUMBER') return ans?.rating != null && ans.rating > 0;
    if (ft === 'PHOTO') return (ans?.photoUrls?.length ?? 0) > 0;
    return ans?.comment?.trim().length > 0;
  }).length;
  const progress = totalRequired > 0 ? Math.round((filledRequired / totalRequired) * 100) : 100;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="bg-white border rounded-xl p-4 shadow-sm">
        <div className="flex justify-between items-start">
          <div>
            <h2 className="font-semibold text-lg">{data.brand} {data.model} {data.year}</h2>
            <p className="text-sm text-gray-500">VIN: {data.vin}</p>
            {template && <p className="text-xs text-gray-400 mt-1">Шаблон: {template.name}</p>}
          </div>
          <button onClick={onDone} className="text-sm text-gray-500 hover:text-gray-700">← Назад</button>
        </div>

        {!template && (
          <div className="mt-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
            Нет активного шаблона анкеты. Обратитесь к администратору.
          </div>
        )}

        {!editable && (
          <div className="mt-3 p-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600">
            Редактирование недоступно — статус: <strong>{STATUS_LABELS[status]?.label || status}</strong>
          </div>
        )}

        {template && editable && (
          <div className="mt-3">
            <div className="flex justify-between text-xs text-gray-500 mb-1">
              <span>Обязательные пункты: {filledRequired}/{totalRequired}</span>
              <span>{progress}%</span>
            </div>
            <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${progress === 100 ? 'bg-green-500' : 'bg-blue-500'}`}
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>
        )}
      </div>

      {msg && (
        <div className={`p-3 rounded-xl text-sm ${
          msg.includes('отправлен') ? 'bg-green-50 text-green-700 border border-green-200'
          : msg.includes('сохранён') ? 'bg-blue-50 text-blue-700 border border-blue-200'
          : 'bg-red-50 text-red-700 border border-red-200'
        }`}>
          {msg}
        </div>
      )}

      {/* Sections */}
      {groups.map(group => (
        <div key={group.id} className="bg-white border rounded-xl overflow-hidden shadow-sm">
          <button
            onClick={() => toggleGroup(group.id)}
            className="w-full flex justify-between items-center px-4 py-3 hover:bg-gray-50 text-left"
          >
            <span className="font-medium">{group.name}</span>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-400">
                {group.items.filter(i => answers[i.id]?.rating != null || answers[i.id]?.comment || (answers[i.id]?.photoUrls?.length ?? 0) > 0).length}/{group.items.length}
              </span>
              <span className="text-gray-400 text-xs">{openGroups.has(group.id) ? '▲' : '▼'}</span>
            </div>
          </button>

          {openGroups.has(group.id) && (
            <div className="divide-y">
              {group.items.length === 0 && (
                <p className="px-4 py-3 text-sm text-gray-400">В этом разделе нет полей</p>
              )}
              {group.items.map(item => {
                const ans = answers[item.id] ?? {
                  itemId: item.id, rating: null, comment: '',
                  photoUrls: [], videoUrls: [], isSkipped: false,
                };
                const hasError = validationErrors.has(item.id);
                return (
                  <div key={item.id} className={`p-4 space-y-2 ${hasError ? 'bg-red-50' : ''}`}>
                    {(item.fieldType || 'TEXTAREA') !== 'CHECKBOX' && (
                      <div>
                        <p className="font-medium text-sm">
                          {item.title}
                          {item.isRequired && <span className="text-red-500 ml-1">*</span>}
                        </p>
                        {item.description && (item.fieldType || 'TEXTAREA') !== 'SELECT' && (
                          <p className="text-xs text-gray-400 mt-0.5">{item.description}</p>
                        )}
                      </div>
                    )}
                    {editable ? (
                      <FieldInput
                        item={item}
                        ans={ans}
                        onChange={(field, value) => setAnswer(item.id, field, value)}
                        hasError={hasError}
                      />
                    ) : (
                      <div className="text-sm text-gray-600 bg-gray-50 rounded-lg px-3 py-2 border">
                        {item.fieldType === 'NUMBER' && ans.rating ? `Оценка: ${ans.rating}/5` : null}
                        {(item.fieldType === 'PHOTO') && ans.photoUrls?.length ? (
                          <a href={ans.photoUrls[0]} target="_blank" rel="noopener noreferrer" className="text-blue-600 underline">Фото</a>
                        ) : null}
                        {(!item.fieldType || ['TEXT', 'TEXTAREA', 'SELECT', 'CHECKBOX'].includes(item.fieldType)) && (ans.comment || '—')}
                      </div>
                    )}
                    {hasError && <p className="text-xs text-red-500">Обязательное поле</p>}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ))}

      {/* Action buttons */}
      {template && editable && (
        <div className="flex gap-3 pb-4">
          <button
            onClick={() => submit(false)}
            disabled={submitting}
            className="px-5 py-2.5 border rounded-xl text-sm hover:bg-gray-50 disabled:opacity-50"
          >
            Сохранить черновик
          </button>
          <button
            onClick={() => submit(true)}
            disabled={submitting || progress < 100}
            className="px-5 py-2.5 bg-blue-600 text-white rounded-xl text-sm hover:bg-blue-700 disabled:opacity-50"
            title={progress < 100 ? 'Заполните все обязательные поля' : ''}
          >
            Отправить на проверку
            {progress < 100 && <span className="ml-1 text-xs opacity-75">({progress}%)</span>}
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Available requests tab ───
function AvailableTab() {
  const [requests, setRequests] = useState<Request[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [search, setSearch] = useState('');

  const load = () => {
    api.get('/diagnostic/work/pending')
      .then(r => setRequests(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const takeWork = async (id: number) => {
    try {
      await api.post(`/diagnostic/work/${id}/assign`);
      setMsg(`Заявка #${id} взята в работу`);
      load();
    } catch (e: any) {
      setMsg(e.response?.data?.message || 'Ошибка');
    }
  };

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return requests;
    return requests.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q)
    );
  }, [requests, search]);

  if (loading) return <div className="py-8 text-center text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded-xl p-3 text-sm">{msg}</div>}
      <div className="flex justify-between items-center gap-3">
        <p className="text-sm text-gray-500">Заявки, ожидающие диагноста</p>
        <div className="w-64">
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm">🔍</span>
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Поиск по VIN, авто..."
              className="pl-8 pr-3 py-2 border rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none" />
          </div>
        </div>
      </div>
      {filtered.length === 0 ? (
        <div className="bg-white border rounded-xl p-10 text-center text-gray-400">
          <p className="text-3xl mb-2">📋</p>
          <p>{requests.length === 0 ? 'Нет доступных заявок' : 'Ничего не найдено'}</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {filtered.map(r => (
            <div key={r.id} className="bg-white border rounded-xl p-4 flex justify-between items-start hover:shadow-sm transition-shadow">
              <div>
                <p className="font-semibold">{r.brand} {r.model} {r.year}</p>
                <p className="text-sm text-gray-500 mt-0.5">VIN: <span className="font-mono">{r.vin}</span></p>
                <p className="text-xs text-gray-400 mt-1">Создана: {formatDate(r.createdAt)}</p>
              </div>
              <button
                onClick={() => takeWork(r.id)}
                className="bg-blue-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-blue-700 shrink-0 ml-3"
              >
                Взять в работу
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── My reports tab ───
function MyReportsTab() {
  const [requests, setRequests] = useState<Request[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeReport, setActiveReport] = useState<number | null>(null);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch] = useState('');

  const load = () => {
    api.get('/diagnostic/work/my?size=50')
      .then(r => setRequests(r.data.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const rework = async (id: number) => {
    try {
      await api.post(`/diagnostic/work/${id}/rework`);
      load();
    } catch (e: any) {
      alert(e.response?.data?.message || 'Ошибка');
    }
  };

  const filtered = useMemo(() => {
    let list = statusFilter === 'ALL' ? requests : requests.filter(r => r.status === statusFilter);
    const q = search.trim().toLowerCase();
    if (q) list = list.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q)
    );
    return list;
  }, [requests, statusFilter, search]);

  const statusOptions = ['ALL', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'];

  if (activeReport != null) {
    return (
      <ReportForm
        requestId={activeReport}
        onDone={() => { setActiveReport(null); load(); }}
      />
    );
  }

  if (loading) return <div className="py-8 text-center text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {/* Filters and search */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="flex gap-2 flex-wrap">
          {statusOptions.map(s => {
            const meta = STATUS_LABELS[s];
            return (
              <button key={s} onClick={() => setStatusFilter(s)}
                className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}>
                {s === 'ALL' ? 'Все' : (
                  <span className={`px-1.5 py-0.5 rounded text-xs ${meta?.color || 'bg-gray-100 text-gray-600'}`}>
                    {meta?.label || s}
                  </span>
                )}
              </button>
            );
          })}
        </div>
        <div className="sm:ml-auto sm:w-64">
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm">🔍</span>
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="VIN, авто..."
              className="pl-8 pr-3 py-2 border rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none" />
          </div>
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="bg-white border rounded-xl p-10 text-center text-gray-400">
          <p className="text-3xl mb-2">📝</p>
          <p>{requests.length === 0 ? 'Нет заявок. Возьмите заявку во вкладке «Доступные».' : 'Ничего не найдено'}</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {filtered.map(r => {
            const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'bg-gray-100 text-gray-600' };
            const editable = isEditable(r.status);
            return (
              <div key={r.id} className="bg-white border rounded-xl p-4 hover:shadow-sm transition-shadow">
                <div className="flex justify-between items-start">
                  <div>
                    <p className="font-semibold">{r.brand} {r.model} {r.year}</p>
                    <p className="text-sm text-gray-500 mt-0.5">VIN: <span className="font-mono">{r.vin}</span></p>
                    <p className="text-xs text-gray-400 mt-1">{formatDate(r.createdAt)}</p>
                  </div>
                  <div className="flex items-center gap-2 shrink-0 ml-3">
                    <span className={`px-3 py-1 rounded-full text-xs font-medium ${s.color}`}>
                      {s.label}
                    </span>
                  </div>
                </div>
                {/* Actions */}
                <div className="flex gap-2 mt-3">
                  {r.status === 'IN_PROGRESS' && (
                    <button
                      onClick={() => setActiveReport(r.id)}
                      className="bg-blue-600 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-blue-700"
                    >
                      Заполнить
                    </button>
                  )}
                  {r.status === 'REJECTED' && (
                    <>
                      <button
                        onClick={() => rework(r.id)}
                        className="bg-orange-500 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-orange-600"
                      >
                        Исправить
                      </button>
                    </>
                  )}
                  {r.status === 'COMPLETED' && (
                    <span className="text-xs text-gray-400 self-center">Ожидает проверки администратора</span>
                  )}
                  {r.status === 'APPROVED' && (
                    <span className="text-xs text-green-600 self-center font-medium">Отчёт одобрен — доступен для аукциона</span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Main ───
const tabs = [
  { path: '', label: 'Доступные заявки' },
  { path: 'my', label: 'Мои отчёты' },
];

export function DiagnosticDashboard() {
  return (
    <div className="max-w-4xl mx-auto p-4 md:p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-1">Панель диагноста</h1>
      <p className="text-sm text-gray-400 mb-6">Принимайте заявки и заполняйте диагностические отчёты</p>

      <div className="flex gap-1 bg-gray-100 p-1 rounded-xl mb-6">
        {tabs.map(({ path, label }) => (
          <NavLink
            key={path}
            to={path === '' ? '/diagnostic' : `/diagnostic/${path}`}
            end={path === ''}
            className={({ isActive }) =>
              `flex-1 text-center py-2 px-3 rounded-lg text-sm font-medium transition-colors ${
                isActive ? 'bg-white text-blue-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`
            }
          >
            {label}
          </NavLink>
        ))}
      </div>

      <Routes>
        <Route index element={<AvailableTab />} />
        <Route path="my" element={<MyReportsTab />} />
      </Routes>
    </div>
  );
}
