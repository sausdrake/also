import { useState, useEffect } from 'react';
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

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  PENDING:     { label: 'Ожидает',    color: 'bg-yellow-100 text-yellow-700' },
  IN_PROGRESS: { label: 'В работе',   color: 'bg-blue-100 text-blue-700' },
  COMPLETED:   { label: 'На проверке',color: 'bg-purple-100 text-purple-700' },
  APPROVED:    { label: 'Одобрена',   color: 'bg-green-100 text-green-700' },
  REJECTED:    { label: 'Отклонена',  color: 'bg-red-100 text-red-700' },
};

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
      // Открываем все группы по умолчанию
      const groups: Group[] = r.data?.template?.groups ?? [];
      setOpenGroups(new Set(groups.map((g: Group) => g.id)));
      // Загружаем существующие ответы
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
          const hasRating = !item.hasRating || (ans?.rating != null && ans.rating > 0);
          const hasComment = ans?.comment?.trim().length > 0;
          if (!hasRating && !hasComment) errors.add(item.id);
        }
      }
    }
    setValidationErrors(errors);
    if (errors.size > 0) {
      // Открываем группы с ошибками
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
      setMsg(isSubmit ? '✅ Отчёт отправлен на проверку!' : '💾 Черновик сохранён');
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
  const totalRequired = groups.flatMap(g => g.items).filter(i => i.isRequired).length;
  const filledRequired = groups.flatMap(g => g.items).filter(i => {
    if (!i.isRequired) return false;
    const ans = answers[i.id];
    return (ans?.rating != null && ans.rating > 0) || ans?.comment?.trim().length > 0;
  }).length;
  const progress = totalRequired > 0 ? Math.round((filledRequired / totalRequired) * 100) : 100;

  return (
    <div className="space-y-4">
      {/* Заголовок */}
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
            ⚠️ Нет активного шаблона анкеты. Обратитесь к администратору для создания шаблона.
          </div>
        )}

        {/* Прогресс */}
        {template && (
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
        <div className={`p-3 rounded-xl text-sm ${msg.includes('✅') ? 'bg-green-50 text-green-700 border border-green-200' : msg.includes('💾') ? 'bg-blue-50 text-blue-700 border border-blue-200' : 'bg-red-50 text-red-700 border border-red-200'}`}>
          {msg}
        </div>
      )}

      {/* Группы шаблона */}
      {groups.map(group => (
        <div key={group.id} className="bg-white border rounded-xl overflow-hidden shadow-sm">
          <button
            onClick={() => toggleGroup(group.id)}
            className="w-full flex justify-between items-center px-4 py-3 hover:bg-gray-50 text-left"
          >
            <span className="font-medium">{group.name}</span>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-400">
                {group.items.filter(i => answers[i.id]?.rating || answers[i.id]?.comment).length}/{group.items.length}
              </span>
              <span className="text-gray-400 text-xs">{openGroups.has(group.id) ? '▲' : '▼'}</span>
            </div>
          </button>

          {openGroups.has(group.id) && (
            <div className="divide-y">
              {group.items.length === 0 && (
                <p className="px-4 py-3 text-sm text-gray-400">В этой группе нет пунктов</p>
              )}
              {group.items.map(item => {
                const ans = answers[item.id] ?? { itemId: item.id, rating: null, comment: '', photoUrls: [], videoUrls: [], isSkipped: false };
                const hasError = validationErrors.has(item.id);
                return (
                  <div key={item.id} className={`p-4 space-y-3 ${hasError ? 'bg-red-50' : ''}`}>
                    <div>
                      <p className="font-medium text-sm">
                        {item.title}
                        {item.isRequired && <span className="text-red-500 ml-1">*</span>}
                      </p>
                      {item.description && <p className="text-xs text-gray-400 mt-0.5">{item.description}</p>}
                    </div>

                    {/* Оценка 1-5 */}
                    {item.hasRating && (
                      <div>
                        <p className="text-xs text-gray-500 mb-1">Оценка</p>
                        <div className="flex gap-1.5">
                          {[1, 2, 3, 4, 5].map(star => (
                            <button
                              key={star}
                              onClick={() => setAnswer(item.id, 'rating', ans.rating === star ? null : star)}
                              className={`w-9 h-9 rounded-lg text-sm font-semibold transition-colors ${
                                ans.rating != null && ans.rating >= star
                                  ? 'bg-yellow-400 text-white'
                                  : 'bg-gray-100 text-gray-500 hover:bg-yellow-100'
                              }`}
                            >
                              {star}
                            </button>
                          ))}
                          {ans.rating && (
                            <span className="self-center text-xs text-gray-400 ml-1">
                              {ans.rating === 1 ? 'Плохо' : ans.rating === 2 ? 'Удовлетворительно' : ans.rating === 3 ? 'Нормально' : ans.rating === 4 ? 'Хорошо' : 'Отлично'}
                            </span>
                          )}
                        </div>
                      </div>
                    )}

                    {/* Комментарий */}
                    <textarea
                      value={ans.comment}
                      onChange={e => setAnswer(item.id, 'comment', e.target.value)}
                      placeholder={item.isRequired ? 'Комментарий (обязателен для обязательных пунктов)' : 'Комментарий'}
                      rows={2}
                      className={`w-full border rounded-lg px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none ${hasError ? 'border-red-400' : 'border-gray-200'}`}
                    />
                    {hasError && <p className="text-xs text-red-500">Заполните оценку или комментарий</p>}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ))}

      {/* Кнопки */}
      {template && (
        <div className="flex gap-3 pb-4">
          <button
            onClick={() => submit(false)}
            disabled={submitting}
            className="px-5 py-2.5 border rounded-xl text-sm hover:bg-gray-50 disabled:opacity-50"
          >
            💾 Сохранить черновик
          </button>
          <button
            onClick={() => submit(true)}
            disabled={submitting}
            className="px-5 py-2.5 bg-blue-600 text-white rounded-xl text-sm hover:bg-blue-700 disabled:opacity-50"
          >
            ✅ Отправить на проверку
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Доступные заявки ───
function AvailableTab() {
  const [requests, setRequests] = useState<Request[]>([]);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');

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
      setMsg(`✅ Заявка #${id} взята в работу`);
      load();
    } catch (e: any) {
      setMsg(e.response?.data?.message || 'Ошибка');
    }
  };

  if (loading) return <div className="py-8 text-center text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded-xl p-3 text-sm">{msg}</div>}
      <p className="text-sm text-gray-500">Заявки на диагностику от пользователей, ожидающие диагноста</p>
      {requests.length === 0 ? (
        <div className="bg-white border rounded-xl p-10 text-center text-gray-400">
          <p className="text-3xl mb-2">📋</p>
          <p>Нет доступных заявок</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {requests.map(r => (
            <div key={r.id} className="bg-white border rounded-xl p-4 flex justify-between items-start hover:shadow-sm transition-shadow">
              <div>
                <p className="font-semibold">{r.brand} {r.model} {r.year}</p>
                <p className="text-sm text-gray-500 mt-0.5">VIN: {r.vin}</p>
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

// ─── Мои отчёты ───
function MyReportsTab() {
  const [requests, setRequests] = useState<Request[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeReport, setActiveReport] = useState<number | null>(null);

  const load = () => {
    api.get('/diagnostic/work/my?size=50')
      .then(r => setRequests(r.data.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

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
      <p className="text-sm text-gray-500">Ваши активные заявки и история</p>
      {requests.length === 0 ? (
        <div className="bg-white border rounded-xl p-10 text-center text-gray-400">
          <p className="text-3xl mb-2">📝</p>
          <p>Нет заявок в работе. Возьмите заявку на вкладке «Доступные».</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {requests.map(r => {
            const s = STATUS_LABELS[r.status] ?? { label: r.status, color: 'bg-gray-100 text-gray-600' };
            return (
              <div key={r.id} className="bg-white border rounded-xl p-4 flex justify-between items-center hover:shadow-sm transition-shadow">
                <div>
                  <p className="font-semibold">{r.brand} {r.model} {r.year}</p>
                  <p className="text-sm text-gray-500 mt-0.5">VIN: {r.vin}</p>
                  <p className="text-xs text-gray-400 mt-1">{formatDate(r.createdAt)}</p>
                </div>
                <div className="flex items-center gap-3 shrink-0 ml-3">
                  <span className={`px-3 py-1 rounded-full text-xs font-medium ${s.color}`}>
                    {s.label}
                  </span>
                  {r.status === 'IN_PROGRESS' && (
                    <button
                      onClick={() => setActiveReport(r.id)}
                      className="bg-blue-600 text-white px-4 py-1.5 rounded-lg text-sm hover:bg-blue-700"
                    >
                      Заполнить
                    </button>
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
