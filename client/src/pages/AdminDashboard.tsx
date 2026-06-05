import { useState, useEffect } from 'react';
import { Routes, Route, NavLink } from 'react-router-dom';
import api from '../api/client';
import { formatDateTime, formatDate } from '../utils/formatDate';

// ─── Types ───
interface Auction {
  id: number; vehicleInfo: string; status: string;
  currentPrice: number; startTime: string; endTime: string; confirmedStart: boolean;
}
interface Report {
  id: number; brand: string; model: string; year: number; vin: string;
  status: string; createdAt: string; userName?: string; diagnosticName?: string;
  rejectedReason?: string;
}
interface UserItem {
  id: number; username: string; email: string; active: boolean;
  roles: string[]; balance?: number; availableBalance?: number;
}
interface Stats {
  totalAuctions: number; activeAuctions: number; createdAuctions: number;
  totalUsers: number; pendingReports: number; pendingRequests: number;
}

const AUCTION_STATUSES = ['ALL', 'CREATED', 'ACTIVE', 'COMPLETED', 'CANCELLED'];
const REPORT_STATUSES  = ['ALL', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'];

const Badge = ({ status }: { status: string }) => {
  const map: Record<string, string> = {
    ACTIVE: 'bg-green-100 text-green-700', CREATED: 'bg-blue-100 text-blue-700',
    COMPLETED: 'bg-purple-100 text-purple-700', CANCELLED: 'bg-red-100 text-red-700',
    PENDING: 'bg-yellow-100 text-yellow-700', APPROVED: 'bg-green-100 text-green-700',
    REJECTED: 'bg-red-100 text-red-700', IN_PROGRESS: 'bg-blue-100 text-blue-700',
  };
  const labels: Record<string, string> = {
    ACTIVE: 'Активен', CREATED: 'Создан', COMPLETED: 'Завершён',
    CANCELLED: 'Отменён', PENDING: 'Ожидает', APPROVED: 'Одобрен',
    REJECTED: 'Отклонён', IN_PROGRESS: 'В работе',
  };
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${map[status] || 'bg-gray-100 text-gray-600'}`}>
      {labels[status] || status}
    </span>
  );
};

function InlinePrompt({ title, placeholder, onConfirm, onCancel }: {
  title: string; placeholder: string;
  onConfirm: (v: string) => void; onCancel: () => void;
}) {
  const [val, setVal] = useState('');
  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-sm p-5 space-y-4">
        <h3 className="font-semibold">{title}</h3>
        <textarea autoFocus value={val} onChange={e => setVal(e.target.value)}
          placeholder={placeholder} rows={3} maxLength={500}
          className="w-full border rounded-lg px-3 py-2 text-sm resize-none focus:ring-2 focus:ring-blue-500 focus:outline-none" />
        <p className="text-xs text-gray-400 text-right">{val.length}/500</p>
        <div className="flex gap-2 justify-end">
          <button onClick={onCancel} className="px-4 py-2 border rounded-lg text-sm hover:bg-gray-50">Отмена</button>
          <button onClick={() => val.trim() && onConfirm(val.trim())} disabled={!val.trim()}
            className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700 disabled:opacity-50">
            Подтвердить
          </button>
        </div>
      </div>
    </div>
  );
}

function ConfirmModal({ message, onYes, onNo }: { message: string; onYes: () => void; onNo: () => void }) {
  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-xl max-w-sm w-full p-5 space-y-4">
        <p className="text-gray-800">{message}</p>
        <div className="flex gap-2 justify-end">
          <button onClick={onNo} className="px-4 py-2 border rounded-lg text-sm hover:bg-gray-50">Отмена</button>
          <button onClick={onYes} className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700">Подтвердить</button>
        </div>
      </div>
    </div>
  );
}

// ─── Dashboard tab ───
function DashboardTab() {
  const [stats, setStats] = useState<Stats | null>(null);
  useEffect(() => { api.get('/admin/dashboard').then(r => setStats(r.data)).catch(() => {}); }, []);
  const colorMap: Record<string, string> = {
    blue: 'bg-blue-50 border-blue-200 text-blue-800',
    green: 'bg-green-50 border-green-200 text-green-800',
    yellow: 'bg-yellow-50 border-yellow-200 text-yellow-800',
    purple: 'bg-purple-50 border-purple-200 text-purple-800',
    orange: 'bg-orange-50 border-orange-200 text-orange-800',
    indigo: 'bg-indigo-50 border-indigo-200 text-indigo-800',
  };
  const cards = stats ? [
    { label: 'Всего аукционов',    value: stats.totalAuctions,    color: 'blue' },
    { label: 'Активных',           value: stats.activeAuctions,   color: 'green' },
    { label: 'Ожидают запуска',    value: stats.createdAuctions,  color: 'yellow' },
    { label: 'Пользователей',      value: stats.totalUsers,       color: 'purple' },
    { label: 'Отчётов на проверке',value: stats.pendingReports,   color: 'orange' },
    { label: 'Заявок ожидает',     value: stats.pendingRequests,  color: 'indigo' },
  ] : [];
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
      {cards.length === 0 && <p className="col-span-3 text-center text-gray-400 py-8">Загрузка статистики...</p>}
      {cards.map(({ label, value, color }) => (
        <div key={label} className={`border rounded-xl p-4 ${colorMap[color]}`}>
          <p className="text-sm opacity-70">{label}</p>
          <p className="text-3xl font-bold mt-1">{value ?? '—'}</p>
        </div>
      ))}
    </div>
  );
}

// ─── Reports tab (ВСЕ отчёты, все статусы) ───
function ReportsTab() {
  const [reports, setReports]   = useState<Report[]>([]);
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [rejectTarget, setRejectTarget] = useState<number | null>(null);
  const [detailReport, setDetailReport] = useState<Report | null>(null);

  const load = (status: string) => {
    setLoading(true);
    const url = status === 'ALL'
      ? '/admin/diagnostic/reports?size=100&sort=createdAt,desc'
      : `/admin/diagnostic/reports?size=100&sort=createdAt,desc&status=${status}`;
    // Используем единый endpoint для всех; если нужна фильтрация по статусу — делаем на клиенте
    api.get('/admin/diagnostic/reports?size=200&sort=createdAt,desc')
      .then(r => {
        const all: Report[] = r.data.content || [];
        setReports(status === 'ALL' ? all : all.filter(r => r.status === status));
      })
      .catch(() => setMsg('Ошибка загрузки отчётов'))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(statusFilter); }, [statusFilter]);

  const approve = async (id: number) => {
    try { await api.post(`/admin/diagnostic/reports/${id}/approve`); setMsg(`Отчёт #${id} одобрен`); load(statusFilter); }
    catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const reject = async (reason: string) => {
    if (rejectTarget == null) return;
    try {
      await api.post(`/admin/diagnostic/reports/${rejectTarget}/reject?reason=${encodeURIComponent(reason)}`);
      setMsg(`Отчёт #${rejectTarget} отклонён`);
      setRejectTarget(null);
      load(statusFilter);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); setRejectTarget(null); }
  };

  const loadDetail = async (id: number) => {
    try {
      const r = await api.get(`/admin/diagnostic/reports/${id}`);
      setDetailReport(r.data);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка загрузки'); }
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {rejectTarget != null && (
        <InlinePrompt
          title={`Причина отклонения отчёта #${rejectTarget}`}
          placeholder="Опишите, что нужно исправить..."
          onConfirm={reject}
          onCancel={() => setRejectTarget(null)}
        />
      )}

      {/* Модал деталей */}
      {detailReport && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-lg p-5 space-y-3 max-h-[80vh] overflow-y-auto">
            <div className="flex justify-between items-start">
              <h3 className="font-semibold">Отчёт #{detailReport.id} — {detailReport.brand} {detailReport.model} {detailReport.year}</h3>
              <button onClick={() => setDetailReport(null)} className="text-gray-400 hover:text-gray-600">✕</button>
            </div>
            <div className="text-sm space-y-1 text-gray-700">
              <p><span className="text-gray-500">VIN:</span> {detailReport.vin}</p>
              <p><span className="text-gray-500">Заявитель:</span> {detailReport.userName || '—'}</p>
              <p><span className="text-gray-500">Диагност:</span> {detailReport.diagnosticName || '—'}</p>
              <p><span className="text-gray-500">Статус:</span> <Badge status={detailReport.status} /></p>
              <p><span className="text-gray-500">Создана:</span> {formatDate(detailReport.createdAt)}</p>
              {detailReport.rejectedReason && (
                <div className="p-3 bg-red-50 border border-red-200 rounded-lg">
                  <p className="text-red-700 font-medium">Причина отклонения:</p>
                  <p className="text-red-600 text-sm mt-1">{detailReport.rejectedReason}</p>
                </div>
              )}
            </div>
            <div className="flex gap-2 pt-2">
              {detailReport.status === 'COMPLETED' && (
                <>
                  <button onClick={() => { approve(detailReport.id); setDetailReport(null); }}
                    className="bg-green-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-green-700">
                    Одобрить
                  </button>
                  <button onClick={() => { setRejectTarget(detailReport.id); setDetailReport(null); }}
                    className="bg-red-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-red-700">
                    Отклонить
                  </button>
                </>
              )}
              <button onClick={() => setDetailReport(null)} className="border px-4 py-2 rounded-lg text-sm">Закрыть</button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded p-3 text-sm">{msg}</div>}

      {/* Фильтр по статусу */}
      <div className="flex gap-2 flex-wrap">
        {REPORT_STATUSES.map(s => (
          <button key={s}
            onClick={() => setStatusFilter(s)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
              statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}>
            {s === 'ALL' ? 'Все' : <Badge status={s} />}
          </button>
        ))}
        <span className="text-xs text-gray-400 self-center ml-2">Найдено: {reports.length}</span>
      </div>

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        {reports.length === 0
          ? <p className="text-center text-gray-400 py-8">Нет отчётов</p>
          : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50 text-gray-500">
                  <tr>{['ID', 'Авто', 'VIN', 'Заявитель', 'Диагност', 'Дата', 'Статус', 'Действия'].map(h =>
                    <th key={h} className="px-3 py-2 text-left whitespace-nowrap">{h}</th>
                  )}</tr>
                </thead>
                <tbody>
                  {reports.map(r => (
                    <tr key={r.id} className="border-t hover:bg-gray-50">
                      <td className="px-3 py-2 text-gray-500">#{r.id}</td>
                      <td className="px-3 py-2 font-medium">{r.brand} {r.model} {r.year}</td>
                      <td className="px-3 py-2 text-gray-500 font-mono text-xs">{r.vin}</td>
                      <td className="px-3 py-2 text-gray-600">{r.userName || '—'}</td>
                      <td className="px-3 py-2 text-gray-600">{r.diagnosticName || '—'}</td>
                      <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{formatDate(r.createdAt)}</td>
                      <td className="px-3 py-2"><Badge status={r.status} /></td>
                      <td className="px-3 py-2">
                        <div className="flex gap-1">
                          <button onClick={() => loadDetail(r.id)}
                            className="text-xs bg-gray-100 text-gray-700 px-2 py-1 rounded hover:bg-gray-200">
                            Детали
                          </button>
                          {r.status === 'COMPLETED' && (
                            <>
                              <button onClick={() => approve(r.id)}
                                className="text-xs bg-green-600 text-white px-2 py-1 rounded hover:bg-green-700">
                                Одобрить
                              </button>
                              <button onClick={() => setRejectTarget(r.id)}
                                className="text-xs bg-red-600 text-white px-2 py-1 rounded hover:bg-red-700">
                                Отклонить
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>
    </div>
  );
}

// ─── Auctions tab (ВСЕ аукционы, фильтр по статусу) ───
function AuctionsTab() {
  const [auctions, setAuctions]     = useState<Auction[]>([]);
  const [allAuctions, setAllAuctions] = useState<Auction[]>([]);
  const [loading, setLoading]       = useState(true);
  const [msg, setMsg]               = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [showCreate, setShowCreate] = useState(false);
  const [vehicles, setVehicles]     = useState<any[]>([]);
  const [cancelTarget, setCancelTarget] = useState<number | null>(null);
  const [form, setForm] = useState({
    vehicleId: '', startingPrice: '', minBidStep: '',
    startTime: '', endTime: '', buyNowPrice: '',
  });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});

  const loadAuctions = () => {
    setLoading(true);
    api.get('/admin/auctions?size=200&sort=id,desc')
      .then(r => {
        const all: Auction[] = r.data.content || [];
        setAllAuctions(all);
      })
      .catch(() => setMsg('Ошибка загрузки аукционов'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAuctions();
    api.get('/admin/vehicles?size=200')
      .then(r => {
        const list = r.data.content || r.data || [];
        setVehicles(list.filter((v: any) => v.status === 'APPROVED'));
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    setAuctions(statusFilter === 'ALL'
      ? allAuctions
      : allAuctions.filter(a => a.status === statusFilter));
  }, [statusFilter, allAuctions]);

  const confirmStart = async (id: number) => {
    try { await api.post(`/admin/auctions/${id}/confirm-start`); setMsg(`Аукцион #${id} подтверждён`); loadAuctions(); }
    catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const cancelAuction = async () => {
    if (cancelTarget == null) return;
    try {
      await api.post(`/admin/auctions/${cancelTarget}/cancel`);
      setMsg(`Аукцион #${cancelTarget} отменён`);
      setCancelTarget(null);
      loadAuctions();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); setCancelTarget(null); }
  };

  const validateForm = () => {
    const errs: Record<string, string> = {};
    if (!form.vehicleId) errs.vehicleId = 'Выберите автомобиль';
    const sp = parseFloat(form.startingPrice);
    if (!form.startingPrice || isNaN(sp) || sp <= 0) errs.startingPrice = 'Введите стартовую цену';
    if (sp > 999_999_999) errs.startingPrice = 'Слишком большая сумма';
    const step = parseFloat(form.minBidStep);
    if (!form.minBidStep || isNaN(step) || step <= 0) errs.minBidStep = 'Введите шаг ставки';
    if (!form.startTime) errs.startTime = 'Укажите дату начала';
    if (!form.endTime) errs.endTime = 'Укажите дату окончания';
    if (form.startTime && form.endTime && form.startTime >= form.endTime)
      errs.endTime = 'Дата окончания должна быть позже начала';
    if (form.buyNowPrice) {
      const bnp = parseFloat(form.buyNowPrice);
      if (isNaN(bnp) || bnp <= 0) errs.buyNowPrice = 'Некорректная цена Buy Now';
    }
    return errs;
  };

  const createAuction = async () => {
    const errs = validateForm();
    if (Object.keys(errs).length > 0) { setFormErrors(errs); return; }
    setFormErrors({});
    try {
      await api.post('/admin/auctions', {
        vehicleId: parseInt(form.vehicleId),
        startingPrice: parseFloat(form.startingPrice),
        minBidStep: parseFloat(form.minBidStep),
        startTime: form.startTime,
        endTime: form.endTime,
        buyNowPrice: form.buyNowPrice ? parseFloat(form.buyNowPrice) : null,
      });
      setMsg('Аукцион создан');
      setShowCreate(false);
      setForm({ vehicleId: '', startingPrice: '', minBidStep: '', startTime: '', endTime: '', buyNowPrice: '' });
      loadAuctions();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  const counts = AUCTION_STATUSES.reduce((acc, s) => {
    acc[s] = s === 'ALL' ? allAuctions.length : allAuctions.filter(a => a.status === s).length;
    return acc;
  }, {} as Record<string, number>);

  return (
    <div className="space-y-4">
      {cancelTarget != null && (
        <ConfirmModal
          message={`Отменить аукцион #${cancelTarget}? Действие необратимо.`}
          onYes={cancelAuction}
          onNo={() => setCancelTarget(null)}
        />
      )}
      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded p-3 text-sm">{msg}</div>}

      <div className="flex justify-between items-center">
        <div className="flex gap-2 flex-wrap">
          {AUCTION_STATUSES.map(s => (
            <button key={s}
              onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {s === 'ALL' ? `Все (${counts.ALL})` : `${s} (${counts[s]})`}
            </button>
          ))}
        </div>
        <button onClick={() => setShowCreate(!showCreate)}
          className="bg-blue-600 text-white px-4 py-2 rounded-lg text-sm shrink-0 ml-3">
          + Создать
        </button>
      </div>

      {showCreate && (
        <div className="bg-white border rounded-xl p-4 shadow-sm space-y-3">
          <h4 className="font-medium">Новый аукцион</h4>
          <div>
            <select value={form.vehicleId} onChange={e => setForm({...form, vehicleId: e.target.value})}
              className={`w-full border rounded-lg px-3 py-2 text-sm ${formErrors.vehicleId ? 'border-red-400' : ''}`}>
              <option value="">Выберите автомобиль (APPROVED)</option>
              {vehicles.map((v: any) => <option key={v.id} value={v.id}>{v.brand} {v.model} {v.year} — {v.vin}</option>)}
            </select>
            {formErrors.vehicleId && <p className="text-red-500 text-xs mt-0.5">{formErrors.vehicleId}</p>}
          </div>
          <div className="grid grid-cols-2 gap-3">
            {[
              { f: 'startingPrice', label: 'Стартовая цена' },
              { f: 'minBidStep',    label: 'Шаг ставки' },
              { f: 'buyNowPrice',   label: 'Buy Now (опционально)' },
            ].map(({ f, label }) => (
              <div key={f}>
                <input type="number" placeholder={label} min={0} max={999999999}
                  value={(form as any)[f]}
                  onChange={e => setForm({...form, [f]: e.target.value})}
                  className={`w-full border rounded-lg px-3 py-2 text-sm ${(formErrors as any)[f] ? 'border-red-400' : ''}`} />
                {(formErrors as any)[f] && <p className="text-red-500 text-xs mt-0.5">{(formErrors as any)[f]}</p>}
              </div>
            ))}
            <div>
              <input type="datetime-local" value={form.startTime}
                onChange={e => setForm({...form, startTime: e.target.value})}
                className={`w-full border rounded-lg px-3 py-2 text-sm ${formErrors.startTime ? 'border-red-400' : ''}`} />
              {formErrors.startTime && <p className="text-red-500 text-xs mt-0.5">{formErrors.startTime}</p>}
            </div>
            <div>
              <input type="datetime-local" value={form.endTime}
                onChange={e => setForm({...form, endTime: e.target.value})}
                className={`w-full border rounded-lg px-3 py-2 text-sm ${formErrors.endTime ? 'border-red-400' : ''}`} />
              {formErrors.endTime && <p className="text-red-500 text-xs mt-0.5">{formErrors.endTime}</p>}
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={createAuction} className="bg-blue-600 text-white px-4 py-2 rounded-lg text-sm">Создать</button>
            <button onClick={() => { setShowCreate(false); setFormErrors({}); }}
              className="border px-4 py-2 rounded-lg text-sm">Отмена</button>
          </div>
        </div>
      )}

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        {auctions.length === 0
          ? <p className="text-center text-gray-400 py-8">Нет аукционов</p>
          : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50 text-gray-500">
                  <tr>{['ID', 'Авто', 'Цена', 'Начало', 'Конец', 'Статус', 'Действия'].map(h =>
                    <th key={h} className="px-3 py-2 text-left whitespace-nowrap">{h}</th>
                  )}</tr>
                </thead>
                <tbody>
                  {auctions.map(a => (
                    <tr key={a.id} className="border-t hover:bg-gray-50">
                      <td className="px-3 py-2 text-gray-500">#{a.id}</td>
                      <td className="px-3 py-2 font-medium max-w-[160px] truncate">{a.vehicleInfo || '—'}</td>
                      <td className="px-3 py-2 whitespace-nowrap">{Number(a.currentPrice).toLocaleString('ru')} сом</td>
                      <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{formatDateTime(a.startTime)}</td>
                      <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{formatDateTime(a.endTime)}</td>
                      <td className="px-3 py-2"><Badge status={a.status} /></td>
                      <td className="px-3 py-2">
                        <div className="flex gap-1">
                          {a.status === 'CREATED' && !a.confirmedStart && (
                            <button onClick={() => confirmStart(a.id)}
                              className="text-xs bg-green-600 text-white px-2 py-1 rounded hover:bg-green-700">
                              Подтвердить
                            </button>
                          )}
                          {(a.status === 'CREATED' || a.status === 'ACTIVE') && (
                            <button onClick={() => setCancelTarget(a.id)}
                              className="text-xs bg-red-500 text-white px-2 py-1 rounded hover:bg-red-600">
                              Отменить
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>
    </div>
  );
}

// ─── Users tab (с балансами) ───
function UsersTab() {
  const [users, setUsers]       = useState<UserItem[]>([]);
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [filterRole, setFilterRole] = useState('');
  const [roleTarget, setRoleTarget] = useState<UserItem | null>(null);
  const [newRole, setNewRole]   = useState('');
  const [adjustTarget, setAdjustTarget] = useState<UserItem | null>(null);
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustComment, setAdjustComment] = useState('');
  const [showBalance, setShowBalance] = useState(false);

  const load = (withBal = showBalance) => {
    const p = filterRole ? `?role=${filterRole}` : '?';
    const sep = filterRole ? '&' : '';
    api.get(`/admin/users${p}${sep}withBalance=${withBal}`)
      .then(r => setUsers(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(showBalance); }, [filterRole, showBalance]);

  const toggleBlock = async (u: UserItem) => {
    try {
      await api.patch(`/admin/users/${u.id}/${u.active ? 'block' : 'unblock'}`);
      setMsg(`${u.username} ${u.active ? 'заблокирован' : 'разблокирован'}`);
      load();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const applyRole = async () => {
    if (!roleTarget || !newRole.trim()) return;
    try {
      await api.patch(`/admin/users/${roleTarget.id}/role`, { role: newRole.trim() });
      setMsg(`Роль ${roleTarget.username} → ${newRole.trim()}`);
      setRoleTarget(null);
      load();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const adjustDeposit = async () => {
    if (!adjustTarget || !adjustAmount || !adjustComment.trim()) return;
    const amount = parseFloat(adjustAmount);
    if (isNaN(amount)) { setMsg('Некорректная сумма'); return; }
    try {
      await api.post(`/admin/users/${adjustTarget.id}/deposit/adjust`, {
        amount, comment: adjustComment.trim(),
      });
      setMsg(`Депозит ${adjustTarget.username} скорректирован на ${amount}`);
      setAdjustTarget(null);
      setAdjustAmount('');
      setAdjustComment('');
      load();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {/* Модал смены роли */}
      {roleTarget && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-xl max-w-sm w-full p-5 space-y-4">
            <h3 className="font-semibold">Сменить роль: {roleTarget.username}</h3>
            <select value={newRole} onChange={e => setNewRole(e.target.value)}
              className="w-full border rounded-lg px-3 py-2 text-sm">
              <option value="">Выберите роль</option>
              <option value="ROLE_USER">Пользователь</option>
              <option value="ROLE_DIAGNOSTIC">Диагностик</option>
              <option value="ROLE_ADMIN">Администратор</option>
            </select>
            <div className="flex gap-2 justify-end">
              <button onClick={() => setRoleTarget(null)} className="px-4 py-2 border rounded-lg text-sm">Отмена</button>
              <button onClick={applyRole} disabled={!newRole}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm disabled:opacity-50">
                Применить
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Модал корректировки депозита */}
      {adjustTarget && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-xl max-w-sm w-full p-5 space-y-4">
            <h3 className="font-semibold">Корректировка депозита: {adjustTarget.username}</h3>
            <p className="text-xs text-gray-500">Положительная сумма — пополнение. Отрицательная — списание.</p>
            <input type="number" placeholder="Сумма (например: 5000 или -1000)"
              value={adjustAmount} onChange={e => setAdjustAmount(e.target.value)}
              className="w-full border rounded-lg px-3 py-2 text-sm" />
            <input type="text" placeholder="Комментарий *"
              value={adjustComment} onChange={e => setAdjustComment(e.target.value)}
              className="w-full border rounded-lg px-3 py-2 text-sm" />
            <div className="flex gap-2 justify-end">
              <button onClick={() => setAdjustTarget(null)} className="px-4 py-2 border rounded-lg text-sm">Отмена</button>
              <button onClick={adjustDeposit} disabled={!adjustAmount || !adjustComment.trim()}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm disabled:opacity-50">
                Применить
              </button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded p-3 text-sm">{msg}</div>}

      <div className="flex gap-3 items-center flex-wrap">
        <select value={filterRole} onChange={e => setFilterRole(e.target.value)}
          className="border rounded-lg px-3 py-2 text-sm">
          <option value="">Все роли</option>
          <option value="ROLE_USER">Пользователи</option>
          <option value="ROLE_DIAGNOSTIC">Диагносты</option>
          <option value="ROLE_ADMIN">Администраторы</option>
        </select>
        <label className="flex items-center gap-2 text-sm cursor-pointer">
          <input type="checkbox" checked={showBalance} onChange={e => setShowBalance(e.target.checked)} />
          Показать баланс
        </label>
        <span className="text-sm text-gray-500">Найдено: {users.length}</span>
      </div>

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-gray-50 text-gray-500">
              <tr>
                {['ID', 'Пользователь', 'Email', 'Роли', 'Статус',
                  ...(showBalance ? ['Баланс', 'Доступно'] : []),
                  'Действия'].map(h =>
                  <th key={h} className="px-3 py-2 text-left whitespace-nowrap">{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {users.map(u => (
                <tr key={u.id} className="border-t hover:bg-gray-50">
                  <td className="px-3 py-2 text-gray-500">#{u.id}</td>
                  <td className="px-3 py-2 font-medium">{u.username}</td>
                  <td className="px-3 py-2 text-gray-500">{u.email}</td>
                  <td className="px-3 py-2">
                    <div className="flex gap-1 flex-wrap">
                      {u.roles.map(r => (
                        <span key={r} className="px-2 py-0.5 bg-blue-50 text-blue-600 rounded text-xs">
                          {r.replace('ROLE_', '')}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${u.active ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                      {u.active ? 'Активен' : 'Заблокирован'}
                    </span>
                  </td>
                  {showBalance && (
                    <>
                      <td className="px-3 py-2 text-right font-mono text-sm">
                        {u.balance != null ? Number(u.balance).toLocaleString('ru') + ' сом' : '—'}
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-sm">
                        {u.availableBalance != null ? Number(u.availableBalance).toLocaleString('ru') + ' сом' : '—'}
                      </td>
                    </>
                  )}
                  <td className="px-3 py-2">
                    <div className="flex gap-1">
                      <button onClick={() => toggleBlock(u)}
                        className={`text-xs px-2 py-1 rounded ${u.active ? 'bg-red-50 text-red-600' : 'bg-green-50 text-green-600'}`}>
                        {u.active ? 'Блок' : 'Разблок'}
                      </button>
                      <button onClick={() => { setRoleTarget(u); setNewRole(u.roles[0] || ''); }}
                        className="text-xs px-2 py-1 rounded bg-gray-50 text-gray-600">Роль</button>
                      <button onClick={() => setAdjustTarget(u)}
                        className="text-xs px-2 py-1 rounded bg-yellow-50 text-yellow-600">Депозит</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

// ─── Settings tab (расширенный) ───
const SETTINGS_META: Record<string, { label: string; description: string; min: number; max: number; step: number }> = {
  deposit_percent: {
    label: 'Депозит для аукциона (%)',
    description: 'Процент от стартовой цены, замораживаемый при входе в аукцион',
    min: 0, max: 50, step: 1,
  },
  default_bid_step_percent: {
    label: 'Минимальный шаг ставки (%)',
    description: 'Минимальный шаг ставки в процентах от текущей цены аукциона',
    min: 0, max: 50, step: 0.5,
  },
  commission_percent: {
    label: 'Комиссия платформы (%)',
    description: 'Процент комиссии, взимаемый с победителя аукциона',
    min: 0, max: 30, step: 0.5,
  },
  diagnostic_deposit_amount: {
    label: 'Залог для диагностики (сом)',
    description: 'Фиксированная сумма заморозки при подаче заявки на диагностику',
    min: 0, max: 100000, step: 1000,
  },
  auction_confirm_hours: {
    label: 'Подтверждение аукциона (ч)',
    description: 'Часов на подтверждение аукциона администратором после создания',
    min: 1, max: 168, step: 1,
  },
  completed_auction_visibility_hours: {
    label: 'Видимость завершённых аукционов (ч)',
    description: 'Часов, в течение которых показываются завершённые аукционы',
    min: 1, max: 8760, step: 24,
  },
  platform_fee_percent: {
    label: 'Комиссия платформы — алиас (%)',
    description: 'Устаревший ключ (алиас commission_percent)',
    min: 0, max: 30, step: 0.5,
  },
};

function SettingsTab() {
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [errors, setErrors]     = useState<Record<string, string>>({});

  useEffect(() => {
    api.get('/admin/settings')
      .then(r => setSettings(r.data))
      .finally(() => setLoading(false));
  }, []);

  const validateSettings = () => {
    const errs: Record<string, string> = {};
    for (const [key, val] of Object.entries(settings)) {
      const meta = SETTINGS_META[key];
      if (!meta) continue;
      const num = Number(val);
      if (isNaN(num) || num < meta.min) errs[key] = `Минимум: ${meta.min}`;
      if (num > meta.max) errs[key] = `Максимум: ${meta.max}`;
    }
    return errs;
  };

  const save = async () => {
    const errs = validateSettings();
    if (Object.keys(errs).length > 0) { setErrors(errs); return; }
    setErrors({});
    const allowed = Object.keys(SETTINGS_META);
    const safe = Object.fromEntries(
      Object.entries(settings).filter(([k]) => allowed.includes(k))
    );
    try {
      await api.put('/admin/settings', safe);
      setMsg('✅ Настройки сохранены');
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  const orderedKeys = Object.keys(SETTINGS_META).filter(k => k in settings);

  return (
    <div className="max-w-xl space-y-4">
      {msg && (
        <div className={`border rounded p-3 text-sm ${msg.includes('✅') ? 'bg-green-50 border-green-200 text-green-800' : 'bg-blue-50 border-blue-200 text-blue-800'}`}>
          {msg}
        </div>
      )}
      <div className="bg-white border rounded-xl shadow-sm divide-y">
        {orderedKeys.map(key => {
          const meta = SETTINGS_META[key];
          return (
            <div key={key} className="p-4">
              <label className="text-sm font-medium text-gray-800 block mb-0.5">{meta.label}</label>
              <p className="text-xs text-gray-400 mb-2">{meta.description}</p>
              <div className="flex gap-3 items-center">
                <input
                  type="number"
                  value={settings[key] ?? ''}
                  min={meta.min}
                  max={meta.max}
                  step={meta.step}
                  onChange={e => {
                    setSettings({ ...settings, [key]: e.target.value });
                    setErrors(prev => ({ ...prev, [key]: '' }));
                  }}
                  className={`w-40 border rounded-lg px-3 py-2 text-sm ${errors[key] ? 'border-red-400' : ''}`}
                />
                <span className="text-xs text-gray-400">
                  {meta.min}–{meta.max}
                </span>
              </div>
              {errors[key] && <p className="text-red-500 text-xs mt-1">{errors[key]}</p>}
            </div>
          );
        })}
      </div>
      <button onClick={save} className="bg-blue-600 text-white px-6 py-2.5 rounded-xl hover:bg-blue-700 font-medium">
        Сохранить настройки
      </button>
    </div>
  );
}

// ─── Templates tab ───
interface TItem { id: number; title: string; description?: string; isRequired: boolean; hasRating: boolean; hasPhoto: boolean; hasVideo: boolean; }
interface TGroup { id: number; name: string; sortOrder: number; items: TItem[]; }
interface Template { id: number; name: string; vehicleType: string; isActive: boolean; groups?: TGroup[]; }

function TemplatesTab() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [selected, setSelected]   = useState<Template | null>(null);
  const [loading, setLoading]     = useState(true);
  const [msg, setMsg]             = useState('');

  const [showTplForm, setShowTplForm] = useState(false);
  const [tplName, setTplName]     = useState('');
  const [tplType, setTplType]     = useState('SEDAN');

  const [showGroupForm, setShowGroupForm] = useState(false);
  const [groupName, setGroupName] = useState('');

  const [activeGroupId, setActiveGroupId] = useState<number | null>(null);
  const [itemTitle, setItemTitle]   = useState('');
  const [itemDesc, setItemDesc]     = useState('');
  const [itemRequired, setItemRequired] = useState(true);
  const [itemRating, setItemRating] = useState(true);
  const [itemPhoto, setItemPhoto]   = useState(false);

  const loadTemplates = () => {
    api.get('/admin/diagnostic/templates').then(r => {
      setTemplates(r.data.content ?? r.data ?? []);
    }).finally(() => setLoading(false));
  };

  const loadTemplate = (id: number) => {
    api.get(`/admin/diagnostic/templates/${id}`).then(r => setSelected(r.data));
  };

  useEffect(() => { loadTemplates(); }, []);

  const createTemplate = async () => {
    if (!tplName.trim()) return;
    try {
      await api.post('/admin/diagnostic/templates', { name: tplName.trim(), vehicleType: tplType, isActive: true });
      setMsg('Шаблон создан');
      setTplName(''); setShowTplForm(false);
      loadTemplates();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const toggleActive = async (t: Template) => {
    try {
      await api.put(`/admin/diagnostic/templates/${t.id}`, {
        name: t.name, vehicleType: t.vehicleType, isActive: !t.isActive,
      });
      setMsg(`Шаблон ${t.isActive ? 'деактивирован' : 'активирован'}`);
      loadTemplates();
      if (selected?.id === t.id) loadTemplate(t.id);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const deleteTemplate = async (id: number) => {
    try {
      await api.delete(`/admin/diagnostic/templates/${id}`);
      setMsg('Шаблон удалён');
      if (selected?.id === id) setSelected(null);
      loadTemplates();
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const addGroup = async () => {
    if (!selected || !groupName.trim()) return;
    try {
      await api.post(`/admin/diagnostic/templates/${selected.id}/groups`, {
        name: groupName.trim(), sortOrder: (selected.groups?.length ?? 0) + 1,
      });
      setGroupName(''); setShowGroupForm(false);
      loadTemplate(selected.id);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const deleteGroup = async (groupId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/groups/${groupId}`); loadTemplate(selected.id); }
    catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const addItem = async (groupId: number) => {
    if (!itemTitle.trim()) return;
    try {
      await api.post(`/admin/diagnostic/groups/${groupId}/items`, {
        title: itemTitle.trim(), description: itemDesc.trim() || null,
        isRequired: itemRequired, hasRating: itemRating, hasPhoto: itemPhoto,
        hasVideo: false, sortOrder: 1,
      });
      setItemTitle(''); setItemDesc(''); setItemRequired(true);
      setItemRating(true); setItemPhoto(false); setActiveGroupId(null);
      if (selected) loadTemplate(selected.id);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  const deleteItem = async (itemId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/items/${itemId}`); loadTemplate(selected.id); }
    catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); }
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded p-3 text-sm">{msg}</div>}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Список шаблонов */}
        <div className="bg-white border rounded-xl shadow-sm">
          <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
            <span className="font-semibold text-sm">Шаблоны</span>
            <button onClick={() => setShowTplForm(v => !v)}
              className="text-xs bg-blue-600 text-white px-2 py-1 rounded hover:bg-blue-700">+ Новый</button>
          </div>
          {showTplForm && (
            <div className="p-3 border-b space-y-2">
              <input value={tplName} onChange={e => setTplName(e.target.value)}
                placeholder="Название шаблона" className="w-full border rounded px-2 py-1.5 text-sm" />
              <select value={tplType} onChange={e => setTplType(e.target.value)}
                className="w-full border rounded px-2 py-1.5 text-sm">
                {['SEDAN','SUV','TRUCK','VAN','MOTORCYCLE','OTHER'].map(t => <option key={t}>{t}</option>)}
              </select>
              <div className="flex gap-2">
                <button onClick={createTemplate} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded">Создать</button>
                <button onClick={() => setShowTplForm(false)} className="text-xs border px-3 py-1.5 rounded">Отмена</button>
              </div>
            </div>
          )}
          <div className="divide-y">
            {templates.length === 0 && <p className="text-center text-gray-400 py-6 text-sm">Нет шаблонов</p>}
            {templates.map(t => (
              <div key={t.id}
                className={`px-3 py-2 cursor-pointer hover:bg-gray-50 ${selected?.id === t.id ? 'bg-blue-50' : ''}`}
                onClick={() => loadTemplate(t.id)}>
                <div className="flex justify-between items-start">
                  <div>
                    <p className="text-sm font-medium">{t.name}</p>
                    <p className="text-xs text-gray-400 mt-0.5">{t.vehicleType}</p>
                  </div>
                  <div className="flex items-center gap-1 ml-2 shrink-0">
                    <button
                      onClick={e => { e.stopPropagation(); toggleActive(t); }}
                      title={t.isActive ? 'Деактивировать' : 'Активировать'}
                      className={`text-xs px-1.5 py-0.5 rounded ${t.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                      {t.isActive ? '✓' : '○'}
                    </button>
                    <button onClick={e => { e.stopPropagation(); deleteTemplate(t.id); }}
                      className="text-xs text-red-400 hover:text-red-600 px-1">✕</button>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Конструктор шаблона */}
        <div className="md:col-span-2">
          {!selected ? (
            <div className="bg-white border rounded-xl p-8 text-center text-gray-400 text-sm">
              Выберите шаблон для редактирования
            </div>
          ) : (
            <div className="bg-white border rounded-xl shadow-sm">
              <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
                <div>
                  <span className="font-semibold">{selected.name}</span>
                  <span className={`ml-2 text-xs px-2 py-0.5 rounded ${selected.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                    {selected.isActive ? 'Активен' : 'Неактивен'}
                  </span>
                </div>
                <button onClick={() => setShowGroupForm(v => !v)}
                  className="text-xs bg-blue-600 text-white px-2 py-1 rounded">+ Группа</button>
              </div>
              {showGroupForm && (
                <div className="p-3 border-b flex gap-2">
                  <input value={groupName} onChange={e => setGroupName(e.target.value)}
                    placeholder="Название группы" className="flex-1 border rounded px-2 py-1.5 text-sm" />
                  <button onClick={addGroup} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded">Добавить</button>
                  <button onClick={() => setShowGroupForm(false)} className="text-xs border px-3 py-1.5 rounded">✕</button>
                </div>
              )}
              <div className="divide-y">
                {(!selected.groups || selected.groups.length === 0) && (
                  <p className="text-center text-gray-400 py-6 text-sm">Нет групп. Добавьте первую группу.</p>
                )}
                {(selected.groups || []).map(group => (
                  <div key={group.id} className="p-3">
                    <div className="flex justify-between items-center mb-2">
                      <span className="font-medium text-sm">{group.name}</span>
                      <div className="flex gap-2">
                        <button onClick={() => setActiveGroupId(activeGroupId === group.id ? null : group.id)}
                          className="text-xs bg-gray-100 px-2 py-1 rounded hover:bg-gray-200">+ Пункт</button>
                        <button onClick={() => deleteGroup(group.id)}
                          className="text-xs text-red-500 hover:text-red-700">✕</button>
                      </div>
                    </div>

                    {activeGroupId === group.id && (
                      <div className="bg-gray-50 rounded-lg p-3 mb-2 space-y-2">
                        <input value={itemTitle} onChange={e => setItemTitle(e.target.value)}
                          placeholder="Название пункта *" className="w-full border rounded px-2 py-1.5 text-sm" />
                        <input value={itemDesc} onChange={e => setItemDesc(e.target.value)}
                          placeholder="Описание (необязательно)" className="w-full border rounded px-2 py-1.5 text-sm" />
                        <div className="flex gap-4 text-xs">
                          {([
                            ['Обязательный', itemRequired, setItemRequired] as const,
                            ['Оценка (1-5)',  itemRating,  setItemRating]  as const,
                            ['Фото',          itemPhoto,   setItemPhoto]   as const,
                          ]).map(([label, val, setter]) => (
                            <label key={label} className="flex items-center gap-1 cursor-pointer">
                              <input type="checkbox" checked={val}
                                onChange={e => setter(e.target.checked)} />
                              {label}
                            </label>
                          ))}
                        </div>
                        <div className="flex gap-2">
                          <button onClick={() => addItem(group.id)}
                            className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded">Добавить пункт</button>
                          <button onClick={() => setActiveGroupId(null)}
                            className="text-xs border px-3 py-1.5 rounded">Отмена</button>
                        </div>
                      </div>
                    )}

                    <div className="space-y-1">
                      {(group.items || []).map(item => (
                        <div key={item.id}
                          className="flex justify-between items-start bg-gray-50 rounded px-2 py-1.5 text-xs">
                          <div>
                            <span>{item.title}</span>
                            {item.isRequired && <span className="ml-1 text-red-500">*</span>}
                            <span className="text-gray-400 ml-2">
                              {[item.hasRating && '⭐', item.hasPhoto && '📷'].filter(Boolean).join(' ')}
                            </span>
                          </div>
                          <button onClick={() => deleteItem(item.id)} className="text-red-400 hover:text-red-600 ml-2">✕</button>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Main ───
const tabs = [
  { path: '',          label: 'Дашборд' },
  { path: 'reports',   label: 'Отчёты' },
  { path: 'auctions',  label: 'Аукционы' },
  { path: 'users',     label: 'Пользователи' },
  { path: 'templates', label: 'Шаблоны' },
  { path: 'settings',  label: 'Настройки' },
];

export function AdminDashboard() {
  return (
    <div className="max-w-6xl mx-auto p-4 md:p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Панель администратора</h1>
      <div className="flex gap-1 bg-gray-100 p-1 rounded-xl mb-6 overflow-x-auto">
        {tabs.map(({ path, label }) => (
          <NavLink key={path} to={path === '' ? '/admin' : `/admin/${path}`} end={path === ''}
            className={({ isActive }) =>
              `flex-1 text-center py-2 px-3 rounded-lg text-sm font-medium transition-colors whitespace-nowrap ${
                isActive ? 'bg-white text-blue-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`}>
            {label}
          </NavLink>
        ))}
      </div>
      <Routes>
        <Route index element={<DashboardTab />} />
        <Route path="reports"   element={<ReportsTab />} />
        <Route path="auctions"  element={<AuctionsTab />} />
        <Route path="users"     element={<UsersTab />} />
        <Route path="templates" element={<TemplatesTab />} />
        <Route path="settings"  element={<SettingsTab />} />
      </Routes>
    </div>
  );
}
