import { useState, useEffect, useMemo } from 'react';
import { Routes, Route, NavLink } from 'react-router-dom';
import api from '../api/client';
import { formatDateTime, formatDate } from '../utils/formatDate';

// ─── Types ───
interface Auction {
  id: number; vehicleInfo: string; status: string; vin?: string;
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

const AUCTION_STATUSES = ['ALL', 'CREATED', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'SOLD'];
const REPORT_STATUSES  = ['ALL', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'];

// ─── Unified Badge ───
const Badge = ({ status }: { status: string }) => {
  const map: Record<string, string> = {
    ACTIVE:      'bg-green-100 text-green-700',
    CREATED:     'bg-blue-100 text-blue-700',
    COMPLETED:   'bg-purple-100 text-purple-700',
    CANCELLED:   'bg-red-100 text-red-700',
    EXPIRED:     'bg-orange-100 text-orange-700',
    SOLD:        'bg-teal-100 text-teal-700',
    PENDING:     'bg-yellow-100 text-yellow-700',
    APPROVED:    'bg-green-100 text-green-700',
    REJECTED:    'bg-red-100 text-red-700',
    IN_PROGRESS: 'bg-blue-100 text-blue-700',
    SUBMITTED:   'bg-purple-100 text-purple-700',
    DRAFT:       'bg-gray-100 text-gray-600',
  };
  const labels: Record<string, string> = {
    ACTIVE: 'Активен', CREATED: 'Создан', COMPLETED: 'Завершён',
    CANCELLED: 'Отменён', EXPIRED: 'Истёк', SOLD: 'Продан',
    PENDING: 'Ожидает', APPROVED: 'Одобрен',
    REJECTED: 'Отклонён', IN_PROGRESS: 'В работе',
    SUBMITTED: 'Отправлен', DRAFT: 'Черновик',
  };
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${map[status] || 'bg-gray-100 text-gray-600'}`}>
      {labels[status] || status}
    </span>
  );
};

// ─── Unified Search Input ───
function SearchInput({ value, onChange, placeholder }: {
  value: string; onChange: (v: string) => void; placeholder: string;
}) {
  return (
    <div className="relative">
      <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm">🔍</span>
      <input
        type="text" value={value} onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        className="pl-8 pr-3 py-2 border rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none"
      />
      {value && (
        <button onClick={() => onChange('')}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-xs">✕</button>
      )}
    </div>
  );
}

// ─── Modals ───
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

// ─── Reports tab ───
function ReportsTab() {
  const [reports, setReports]   = useState<Report[]>([]);
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [msgErr, setMsgErr]     = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch]     = useState('');
  const [rejectTarget, setRejectTarget] = useState<number | null>(null);
  const [detailReport, setDetailReport] = useState<Report | null>(null);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); };

  const load = () => {
    setLoading(true);
    api.get('/admin/diagnostic/reports?size=200&sort=createdAt,desc')
      .then(r => setReports(r.data.content || []))
      .catch(() => notify('Ошибка загрузки отчётов', true))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const filtered = useMemo(() => {
    let list = statusFilter === 'ALL' ? reports : reports.filter(r => r.status === statusFilter);
    const q = search.trim().toLowerCase();
    if (q) list = list.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q) ||
      r.diagnosticName?.toLowerCase().includes(q) ||
      r.userName?.toLowerCase().includes(q) ||
      r.status?.toLowerCase().includes(q)
    );
    return list;
  }, [reports, statusFilter, search]);

  const approve = async (id: number) => {
    try { await api.post(`/admin/diagnostic/reports/${id}/approve`); notify(`Отчёт #${id} одобрен`); load(); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const reject = async (reason: string) => {
    if (rejectTarget == null) return;
    try {
      await api.post(`/admin/diagnostic/reports/${rejectTarget}/reject?reason=${encodeURIComponent(reason)}`);
      notify(`Отчёт #${rejectTarget} отклонён`);
      setRejectTarget(null); load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); setRejectTarget(null); }
  };

  const loadDetail = async (id: number) => {
    try { const r = await api.get(`/admin/diagnostic/reports/${id}`); setDetailReport(r.data); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка загрузки', true); }
  };

  // Display COMPLETED as "Отправлен" in UI
  const displayStatus = (s: string) => s === 'COMPLETED' ? 'SUBMITTED' : s;

  if (loading) return <div className="p-4 text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {rejectTarget != null && (
        <InlinePrompt
          title={`Причина отклонения отчёта #${rejectTarget}`}
          placeholder="Опишите, что нужно исправить..."
          onConfirm={reject} onCancel={() => setRejectTarget(null)}
        />
      )}

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
              <p><span className="text-gray-500">Статус:</span> <Badge status={displayStatus(detailReport.status)} /></p>
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
                    className="bg-green-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-green-700">Одобрить</button>
                  <button onClick={() => { setRejectTarget(detailReport.id); setDetailReport(null); }}
                    className="bg-red-600 text-white px-4 py-2 rounded-lg text-sm hover:bg-red-700">Отклонить</button>
                </>
              )}
              <button onClick={() => setDetailReport(null)} className="border px-4 py-2 rounded-lg text-sm">Закрыть</button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`rounded-lg p-3 text-sm border ${msgErr ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>{msg}</div>}

      {/* Filters and search */}
      <div className="flex flex-col sm:flex-row gap-3">
        <div className="flex gap-2 flex-wrap items-center">
          {REPORT_STATUSES.map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {s === 'ALL' ? 'Все' : <Badge status={s === 'COMPLETED' ? 'SUBMITTED' : s} />}
            </button>
          ))}
          <span className="text-xs text-gray-400">Найдено: {filtered.length}</span>
        </div>
        <div className="sm:ml-auto sm:w-72">
          <SearchInput value={search} onChange={setSearch} placeholder="VIN, авто, диагност, статус..." />
        </div>
      </div>

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        {filtered.length === 0
          ? <p className="text-center text-gray-400 py-8">Нет отчётов</p>
          : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50 text-gray-500 text-xs">
                  <tr>{['ID', 'Авто', 'VIN', 'Заявитель', 'Диагност', 'Дата', 'Статус', 'Действия'].map(h =>
                    <th key={h} className="px-3 py-2.5 text-left font-medium whitespace-nowrap">{h}</th>
                  )}</tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {filtered.map(r => (
                    <tr key={r.id} className="hover:bg-gray-50">
                      <td className="px-3 py-2.5 text-gray-400 text-xs">#{r.id}</td>
                      <td className="px-3 py-2.5 font-medium">{r.brand} {r.model} {r.year}</td>
                      <td className="px-3 py-2.5 text-gray-500 font-mono text-xs">{r.vin}</td>
                      <td className="px-3 py-2.5 text-gray-600">{r.userName || '—'}</td>
                      <td className="px-3 py-2.5 text-gray-600">{r.diagnosticName || '—'}</td>
                      <td className="px-3 py-2.5 text-gray-400 whitespace-nowrap text-xs">{formatDate(r.createdAt)}</td>
                      <td className="px-3 py-2.5"><Badge status={displayStatus(r.status)} /></td>
                      <td className="px-3 py-2.5">
                        <div className="flex gap-1">
                          <button onClick={() => loadDetail(r.id)}
                            className="text-xs bg-gray-100 text-gray-700 px-2 py-1 rounded hover:bg-gray-200">Детали</button>
                          {r.status === 'COMPLETED' && (
                            <>
                              <button onClick={() => approve(r.id)}
                                className="text-xs bg-green-600 text-white px-2 py-1 rounded hover:bg-green-700">Одобрить</button>
                              <button onClick={() => setRejectTarget(r.id)}
                                className="text-xs bg-red-600 text-white px-2 py-1 rounded hover:bg-red-700">Отклонить</button>
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

// ─── Auctions tab ───
function AuctionsTab() {
  const [allAuctions, setAllAuctions] = useState<Auction[]>([]);
  const [loading, setLoading]         = useState(true);
  const [msg, setMsg]                 = useState('');
  const [msgErr, setMsgErr]           = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch]           = useState('');
  const [showCreate, setShowCreate]   = useState(false);
  const [vehicles, setVehicles]       = useState<any[]>([]);
  const [cancelTarget, setCancelTarget] = useState<number | null>(null);
  const [form, setForm] = useState({
    vehicleId: '', startingPrice: '', minBidStep: '',
    startTime: '', endTime: '', buyNowPrice: '', reservePrice: '',
  });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});

  const notifyA = (text: string, err = false) => { setMsg(text); setMsgErr(err); };

  const loadAuctions = () => {
    setLoading(true);
    api.get('/admin/auctions?size=200&sort=id,desc')
      .then(r => setAllAuctions(r.data.content || []))
      .catch(() => notifyA('Ошибка загрузки аукционов', true))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAuctions();
    // Load vehicles with APPROVED diagnostic reports (vehicle status = APPROVED)
    api.get('/admin/vehicles/approved')
      .then(r => setVehicles(Array.isArray(r.data) ? r.data : (r.data.content || [])))
      .catch(() => {});
  }, []);

  const auctions = useMemo(() => {
    let list = statusFilter === 'ALL' ? allAuctions : allAuctions.filter(a => a.status === statusFilter);
    const q = search.trim().toLowerCase();
    if (q) list = list.filter(a =>
      a.vehicleInfo?.toLowerCase().includes(q) ||
      a.vin?.toLowerCase().includes(q) ||
      a.status?.toLowerCase().includes(q) ||
      String(a.id).includes(q)
    );
    return list;
  }, [allAuctions, statusFilter, search]);

  const counts = useMemo(() =>
    AUCTION_STATUSES.reduce((acc, s) => {
      acc[s] = s === 'ALL' ? allAuctions.length : allAuctions.filter(a => a.status === s).length;
      return acc;
    }, {} as Record<string, number>),
    [allAuctions]
  );

  const confirmStart = async (id: number) => {
    try { await api.post(`/admin/auctions/${id}/confirm-start`); notifyA(`Аукцион #${id} подтверждён`); loadAuctions(); }
    catch (e: any) { notifyA(e.response?.data?.message || 'Ошибка подтверждения аукциона', true); }
  };

  const cancelAuction = async () => {
    if (cancelTarget == null) return;
    try {
      await api.post(`/admin/auctions/${cancelTarget}/cancel`);
      notifyA(`Аукцион #${cancelTarget} отменён`);
      setCancelTarget(null); loadAuctions();
    } catch (e: any) { notifyA(e.response?.data?.message || 'Ошибка отмены аукциона', true); setCancelTarget(null); }
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
        startTime: new Date(form.startTime).toISOString(),
        endTime: new Date(form.endTime).toISOString(),
        buyNowPrice: form.buyNowPrice ? parseFloat(form.buyNowPrice) : null,
        reservePrice: form.reservePrice ? parseFloat(form.reservePrice) : null,
      });
      notifyA('Аукцион успешно создан');
      setShowCreate(false);
      setForm({ vehicleId: '', startingPrice: '', minBidStep: '', startTime: '', endTime: '', buyNowPrice: '', reservePrice: '' });
      loadAuctions();
    } catch (e: any) { notifyA(e.response?.data?.message || 'Не удалось создать аукцион', true); }
  };

  if (loading) return <div className="p-4 text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {cancelTarget != null && (
        <ConfirmModal
          message={`Отменить аукцион #${cancelTarget}? Действие необратимо.`}
          onYes={cancelAuction} onNo={() => setCancelTarget(null)}
        />
      )}
      {msg && <div className={`rounded-lg p-3 text-sm border ${msgErr ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>{msg}</div>}

      {/* Filters, search, create button */}
      <div className="space-y-3">
        <div className="flex flex-wrap gap-2 items-center">
          {AUCTION_STATUSES.map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {s === 'ALL' ? `Все (${counts.ALL})` : `${s} (${counts[s] || 0})`}
            </button>
          ))}
          <button onClick={() => setShowCreate(!showCreate)}
            className="ml-auto bg-blue-600 text-white px-4 py-2 rounded-lg text-sm shrink-0">
            + Создать
          </button>
        </div>
        <SearchInput value={search} onChange={setSearch} placeholder="Поиск по VIN, авто, статусу, ID..." />
      </div>

      {showCreate && (
        <div className="bg-white border rounded-xl p-4 shadow-sm space-y-3">
          <h4 className="font-medium">Новый аукцион</h4>
          {vehicles.length === 0 ? (
            <div className="p-4 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
              Нет автомобилей с одобренным диагностическим отчётом. Сначала создайте и одобрите диагностику.
            </div>
          ) : (
            <>
              <div>
                <select value={form.vehicleId} onChange={e => setForm({...form, vehicleId: e.target.value})}
                  className={`w-full border rounded-lg px-3 py-2 text-sm ${formErrors.vehicleId ? 'border-red-400' : ''}`}>
                  <option value="">— Выберите автомобиль —</option>
                  {vehicles.map((v: any) => (
                    <option key={v.id} value={v.id}>{v.brand} {v.model} {v.year} — VIN: {v.vin}</option>
                  ))}
                </select>
                {formErrors.vehicleId && <p className="text-red-500 text-xs mt-0.5">{formErrors.vehicleId}</p>}
              </div>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { f: 'startingPrice', label: 'Стартовая цена' },
                  { f: 'minBidStep',    label: 'Шаг ставки' },
                  { f: 'reservePrice',  label: 'Резервная цена (опц.)' },
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
                  <label className="text-xs text-gray-500 mb-1 block">Дата начала</label>
                  <input type="datetime-local" value={form.startTime}
                    onChange={e => setForm({...form, startTime: e.target.value})}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${formErrors.startTime ? 'border-red-400' : ''}`} />
                  {formErrors.startTime && <p className="text-red-500 text-xs mt-0.5">{formErrors.startTime}</p>}
                </div>
                <div>
                  <label className="text-xs text-gray-500 mb-1 block">Дата окончания</label>
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
            </>
          )}
        </div>
      )}

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        {auctions.length === 0
          ? <p className="text-center text-gray-400 py-8">Нет аукционов</p>
          : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-gray-50 text-gray-500 text-xs">
                  <tr>{['ID', 'Авто', 'Цена', 'Начало', 'Конец', 'Статус', 'Действия'].map(h =>
                    <th key={h} className="px-3 py-2.5 text-left font-medium whitespace-nowrap">{h}</th>
                  )}</tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {auctions.map(a => (
                    <tr key={a.id} className="hover:bg-gray-50">
                      <td className="px-3 py-2.5 text-gray-400 text-xs">#{a.id}</td>
                      <td className="px-3 py-2.5 font-medium max-w-[160px] truncate">{a.vehicleInfo || '—'}</td>
                      <td className="px-3 py-2.5 whitespace-nowrap">{Number(a.currentPrice).toLocaleString('ru')} сом</td>
                      <td className="px-3 py-2.5 text-gray-400 whitespace-nowrap text-xs">{formatDateTime(a.startTime)}</td>
                      <td className="px-3 py-2.5 text-gray-400 whitespace-nowrap text-xs">{formatDateTime(a.endTime)}</td>
                      <td className="px-3 py-2.5"><Badge status={a.status} /></td>
                      <td className="px-3 py-2.5">
                        <div className="flex gap-1">
                          {a.status === 'CREATED' && !a.confirmedStart && (
                            <button onClick={() => confirmStart(a.id)}
                              className="text-xs bg-green-600 text-white px-2 py-1 rounded hover:bg-green-700">Подтвердить</button>
                          )}
                          {(a.status === 'CREATED' || a.status === 'ACTIVE') && (
                            <button onClick={() => setCancelTarget(a.id)}
                              className="text-xs bg-red-500 text-white px-2 py-1 rounded hover:bg-red-600">Отменить</button>
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

// ─── Users tab ───
function UsersTab() {
  const [users, setUsers]       = useState<UserItem[]>([]);
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [msgErr, setMsgErr]     = useState(false);
  const [filterRole, setFilterRole] = useState('');
  const [search, setSearch]     = useState('');
  const [roleTarget, setRoleTarget] = useState<UserItem | null>(null);
  const [newRole, setNewRole]   = useState('');
  const [adjustTarget, setAdjustTarget] = useState<UserItem | null>(null);
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustComment, setAdjustComment] = useState('');
  const [showBalance, setShowBalance] = useState(false);

  const notifyU = (text: string, err = false) => { setMsg(text); setMsgErr(err); };

  const load = (withBal = showBalance) => {
    const p = filterRole ? `?role=${filterRole}` : '?';
    const sep = filterRole ? '&' : '';
    api.get(`/admin/users${p}${sep}withBalance=${withBal}`)
      .then(r => setUsers(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(showBalance); }, [filterRole, showBalance]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return users;
    return users.filter(u =>
      u.username?.toLowerCase().includes(q) ||
      u.email?.toLowerCase().includes(q) ||
      u.roles.some(r => r.toLowerCase().includes(q))
    );
  }, [users, search]);

  const toggleBlock = async (u: UserItem) => {
    try {
      await api.patch(`/admin/users/${u.id}/${u.active ? 'block' : 'unblock'}`);
      notifyU(`${u.username} ${u.active ? 'заблокирован' : 'разблокирован'}`);
      load();
    } catch (e: any) { notifyU(e.response?.data?.message || 'Ошибка', true); }
  };

  const applyRole = async () => {
    if (!roleTarget || !newRole.trim()) return;
    try {
      await api.patch(`/admin/users/${roleTarget.id}/role`, { role: newRole.trim() });
      notifyU(`Роль ${roleTarget.username} → ${newRole.trim()}`);
      setRoleTarget(null); load();
    } catch (e: any) { notifyU(e.response?.data?.message || 'Ошибка', true); }
  };

  const adjustDeposit = async () => {
    if (!adjustTarget || !adjustAmount || !adjustComment.trim()) return;
    const amount = parseFloat(adjustAmount);
    if (isNaN(amount)) { notifyU('Некорректная сумма', true); return; }
    try {
      await api.post(`/admin/users/${adjustTarget.id}/deposit/adjust`, { amount, comment: adjustComment.trim() });
      notifyU(`Депозит ${adjustTarget.username} скорректирован на ${amount}`);
      setAdjustTarget(null); setAdjustAmount(''); setAdjustComment(''); load();
    } catch (e: any) { notifyU(e.response?.data?.message || 'Ошибка', true); }
  };

  if (loading) return <div className="p-4 text-gray-400">Загрузка...</div>;

  return (
    <div className="space-y-4">
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
                className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm disabled:opacity-50">Применить</button>
            </div>
          </div>
        </div>
      )}

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
                className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm disabled:opacity-50">Применить</button>
            </div>
          </div>
        </div>
      )}

      {msg && <div className={`rounded-lg p-3 text-sm border ${msgErr ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>{msg}</div>}

      {/* Filters */}
      <div className="flex flex-col sm:flex-row gap-3">
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
          <span className="text-xs text-gray-500">Найдено: {filtered.length}</span>
        </div>
        <div className="sm:ml-auto sm:w-72">
          <SearchInput value={search} onChange={setSearch} placeholder="Имя, email, роль..." />
        </div>
      </div>

      <div className="bg-white border rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs">
              <tr>
                {['ID', 'Пользователь', 'Email', 'Роли', 'Статус',
                  ...(showBalance ? ['Баланс', 'Доступно'] : []),
                  'Действия'].map(h =>
                  <th key={h} className="px-3 py-2.5 text-left font-medium whitespace-nowrap">{h}</th>
                )}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {filtered.map(u => (
                <tr key={u.id} className="hover:bg-gray-50">
                  <td className="px-3 py-2.5 text-gray-400 text-xs">#{u.id}</td>
                  <td className="px-3 py-2.5 font-medium">{u.username}</td>
                  <td className="px-3 py-2.5 text-gray-500">{u.email}</td>
                  <td className="px-3 py-2.5">
                    <div className="flex gap-1 flex-wrap">
                      {u.roles.map(r => (
                        <span key={r} className="px-2 py-0.5 bg-blue-50 text-blue-600 rounded text-xs">
                          {r.replace('ROLE_', '')}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-3 py-2.5">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${u.active ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                      {u.active ? 'Активен' : 'Заблокирован'}
                    </span>
                  </td>
                  {showBalance && (
                    <>
                      <td className="px-3 py-2.5 text-right font-mono text-xs">
                        {u.balance != null ? Number(u.balance).toLocaleString('ru') + ' сом' : '—'}
                      </td>
                      <td className="px-3 py-2.5 text-right font-mono text-xs">
                        {u.availableBalance != null ? Number(u.availableBalance).toLocaleString('ru') + ' сом' : '—'}
                      </td>
                    </>
                  )}
                  <td className="px-3 py-2.5">
                    <div className="flex gap-1">
                      <button onClick={() => toggleBlock(u)}
                        className={`text-xs px-2 py-1 rounded ${u.active ? 'bg-red-50 text-red-600 hover:bg-red-100' : 'bg-green-50 text-green-600 hover:bg-green-100'}`}>
                        {u.active ? 'Блок' : 'Разблок'}
                      </button>
                      <button onClick={() => { setRoleTarget(u); setNewRole(u.roles[0] || ''); }}
                        className="text-xs px-2 py-1 rounded bg-gray-50 text-gray-600 hover:bg-gray-100">Роль</button>
                      <button onClick={() => setAdjustTarget(u)}
                        className="text-xs px-2 py-1 rounded bg-yellow-50 text-yellow-600 hover:bg-yellow-100">Депозит</button>
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

// ─── Settings tab ───
const SETTINGS_META: Record<string, { label: string; description: string; min: number; max: number; step: number }> = {
  deposit_percent: { label: 'Депозит для аукциона (%)', description: 'Процент от стартовой цены, замораживаемый при входе в аукцион', min: 0, max: 50, step: 1 },
  default_bid_step_percent: { label: 'Минимальный шаг ставки (%)', description: 'Минимальный шаг ставки в процентах от текущей цены аукциона', min: 0, max: 50, step: 0.5 },
  commission_percent: { label: 'Комиссия платформы (%)', description: 'Процент комиссии, взимаемый с победителя аукциона', min: 0, max: 30, step: 0.5 },
  diagnostic_deposit_amount: { label: 'Залог для диагностики (сом)', description: 'Фиксированная сумма заморозки при подаче заявки на диагностику', min: 0, max: 100000, step: 1000 },
  auction_confirm_hours: { label: 'Подтверждение аукциона (ч)', description: 'Часов на подтверждение аукциона администратором после создания', min: 1, max: 168, step: 1 },
  completed_auction_visibility_hours: { label: 'Видимость завершённых аукционов (ч)', description: 'Часов, в течение которых показываются завершённые аукционы', min: 1, max: 8760, step: 24 },
  platform_fee_percent: { label: 'Комиссия платформы — алиас (%)', description: 'Устаревший ключ (алиас commission_percent)', min: 0, max: 30, step: 0.5 },
};

function SettingsTab() {
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [msgErr, setMsgErr]     = useState(false);
  const [errors, setErrors]     = useState<Record<string, string>>({});

  useEffect(() => {
    api.get('/admin/settings').then(r => setSettings(r.data)).finally(() => setLoading(false));
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
    const safe = Object.fromEntries(Object.entries(settings).filter(([k]) => k in SETTINGS_META));
    try {
      await api.put('/admin/settings', safe);
      setMsg('Настройки сохранены'); setMsgErr(false);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); setMsgErr(true); }
  };

  if (loading) return <div className="p-4 text-gray-400">Загрузка...</div>;

  return (
    <div className="max-w-xl space-y-4">
      {msg && (
        <div className={`rounded-lg p-3 text-sm border ${msgErr ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>{msg}</div>
      )}
      <div className="bg-white border rounded-xl shadow-sm divide-y">
        {Object.keys(SETTINGS_META).filter(k => k in settings).map(key => {
          const meta = SETTINGS_META[key];
          return (
            <div key={key} className="p-4">
              <label className="text-sm font-medium text-gray-800 block mb-0.5">{meta.label}</label>
              <p className="text-xs text-gray-400 mb-2">{meta.description}</p>
              <div className="flex gap-3 items-center">
                <input type="number" value={settings[key] ?? ''} min={meta.min} max={meta.max} step={meta.step}
                  onChange={e => { setSettings({ ...settings, [key]: e.target.value }); setErrors(prev => ({ ...prev, [key]: '' })); }}
                  className={`w-40 border rounded-lg px-3 py-2 text-sm ${errors[key] ? 'border-red-400' : ''}`} />
                <span className="text-xs text-gray-400">{meta.min}–{meta.max}</span>
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

// ─── Templates tab (redesigned with fieldType) ───

const FIELD_TYPES = [
  { value: 'TEXT',     label: 'Текст',       icon: 'T' },
  { value: 'TEXTAREA', label: 'Длинный текст', icon: '≡' },
  { value: 'NUMBER',   label: 'Число',        icon: '#' },
  { value: 'SELECT',   label: 'Выбор',        icon: '▾' },
  { value: 'CHECKBOX', label: 'Флажок',       icon: '☑' },
  { value: 'PHOTO',    label: 'Фото',         icon: '📷' },
];

const VEHICLE_TYPES = ['SEDAN', 'SUV', 'TRUCK', 'VAN', 'MOTORCYCLE', 'OTHER'];

interface TItem {
  id: number; title: string; description?: string;
  fieldType: string; isRequired: boolean;
  hasRating: boolean; hasPhoto: boolean; hasVideo: boolean;
}
interface TSection { id: number; name: string; sortOrder: number; items: TItem[]; }
interface Template { id: number; name: string; vehicleType: string; isActive: boolean; groups?: TSection[]; }

function TemplatesTab() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [selected, setSelected]   = useState<Template | null>(null);
  const [loading, setLoading]     = useState(true);
  const [msg, setMsg]             = useState('');
  const [msgErr, setMsgErr]       = useState(false);
  const [tplSearch, setTplSearch] = useState('');

  const notifyT = (text: string, err = false) => { setMsg(text); setMsgErr(err); };

  const [showTplForm, setShowTplForm] = useState(false);
  const [tplName, setTplName]     = useState('');
  const [tplType, setTplType]     = useState('SEDAN');

  const [showSectionForm, setShowSectionForm] = useState(false);
  const [sectionName, setSectionName] = useState('');

  const [activeSection, setActiveSection] = useState<number | null>(null);
  const [itemTitle, setItemTitle]   = useState('');
  const [itemDesc, setItemDesc]     = useState('');
  const [itemRequired, setItemRequired] = useState(true);
  const [itemFieldType, setItemFieldType] = useState('TEXT');

  const loadTemplates = () => {
    api.get('/admin/diagnostic/templates').then(r => {
      setTemplates(r.data.content ?? r.data ?? []);
    }).finally(() => setLoading(false));
  };

  const loadTemplate = (id: number) => {
    api.get(`/admin/diagnostic/templates/${id}`).then(r => setSelected(r.data));
  };

  useEffect(() => { loadTemplates(); }, []);

  const filteredTemplates = useMemo(() => {
    const q = tplSearch.trim().toLowerCase();
    if (!q) return templates;
    return templates.filter(t =>
      t.name.toLowerCase().includes(q) ||
      t.vehicleType?.toLowerCase().includes(q)
    );
  }, [templates, tplSearch]);

  const createTemplate = async () => {
    if (!tplName.trim()) return;
    try {
      await api.post('/admin/diagnostic/templates', { name: tplName.trim(), vehicleType: tplType, isActive: true });
      notifyT('Шаблон создан'); setTplName(''); setShowTplForm(false); loadTemplates();
    } catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const toggleActive = async (t: Template) => {
    try {
      await api.put(`/admin/diagnostic/templates/${t.id}`, { name: t.name, vehicleType: t.vehicleType, isActive: !t.isActive });
      notifyT(`Шаблон ${t.isActive ? 'деактивирован' : 'активирован'}`);
      loadTemplates();
      if (selected?.id === t.id) loadTemplate(t.id);
    } catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const deleteTemplate = async (id: number) => {
    try {
      await api.delete(`/admin/diagnostic/templates/${id}`);
      notifyT('Шаблон удалён');
      if (selected?.id === id) setSelected(null);
      loadTemplates();
    } catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const addSection = async () => {
    if (!selected || !sectionName.trim()) return;
    try {
      await api.post(`/admin/diagnostic/templates/${selected.id}/groups`, {
        name: sectionName.trim(), sortOrder: (selected.groups?.length ?? 0) + 1,
      });
      setSectionName(''); setShowSectionForm(false); loadTemplate(selected.id);
    } catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const deleteSection = async (groupId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/groups/${groupId}`); loadTemplate(selected.id); }
    catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const addField = async (groupId: number) => {
    if (!itemTitle.trim()) return;
    try {
      await api.post(`/admin/diagnostic/groups/${groupId}/items`, {
        title: itemTitle.trim(),
        description: itemDesc.trim() || null,
        fieldType: itemFieldType,
        isRequired: itemRequired,
        hasPhoto: itemFieldType === 'PHOTO',
        hasRating: itemFieldType === 'NUMBER',
        hasVideo: false,
        sortOrder: 1,
      });
      setItemTitle(''); setItemDesc(''); setItemRequired(true);
      setItemFieldType('TEXT'); setActiveSection(null);
      if (selected) loadTemplate(selected.id);
    } catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  const deleteField = async (itemId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/items/${itemId}`); loadTemplate(selected.id); }
    catch (e: any) { notifyT(e.response?.data?.message || 'Ошибка', true); }
  };

  if (loading) return <div className="p-4 text-gray-400">Загрузка...</div>;

  const ftMeta = (ft: string) => FIELD_TYPES.find(x => x.value === ft) || { label: ft, icon: '?' };

  return (
    <div className="space-y-4">
      {msg && <div className={`rounded-lg p-3 text-sm border ${msgErr ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>{msg}</div>}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Template list */}
        <div className="bg-white border rounded-xl shadow-sm overflow-hidden">
          <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
            <span className="font-semibold text-sm">Шаблоны</span>
            <button onClick={() => setShowTplForm(v => !v)}
              className="text-xs bg-blue-600 text-white px-2 py-1 rounded hover:bg-blue-700">+ Новый</button>
          </div>

          {showTplForm && (
            <div className="p-3 border-b space-y-2 bg-blue-50">
              <input value={tplName} onChange={e => setTplName(e.target.value)}
                placeholder="Название шаблона" className="w-full border rounded px-2 py-1.5 text-sm" />
              <select value={tplType} onChange={e => setTplType(e.target.value)}
                className="w-full border rounded px-2 py-1.5 text-sm">
                {VEHICLE_TYPES.map(t => <option key={t}>{t}</option>)}
              </select>
              <div className="flex gap-2">
                <button onClick={createTemplate} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded">Создать</button>
                <button onClick={() => setShowTplForm(false)} className="text-xs border px-3 py-1.5 rounded">Отмена</button>
              </div>
            </div>
          )}

          <div className="p-2 border-b">
            <SearchInput value={tplSearch} onChange={setTplSearch} placeholder="Поиск шаблона..." />
          </div>

          <div className="divide-y max-h-[500px] overflow-y-auto">
            {filteredTemplates.length === 0 && <p className="text-center text-gray-400 py-6 text-sm">Нет шаблонов</p>}
            {filteredTemplates.map(t => (
              <div key={t.id}
                className={`px-3 py-2.5 cursor-pointer hover:bg-gray-50 transition-colors ${selected?.id === t.id ? 'bg-blue-50 border-l-2 border-blue-500' : ''}`}
                onClick={() => loadTemplate(t.id)}>
                <div className="flex justify-between items-start">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium truncate">{t.name}</p>
                    <p className="text-xs text-gray-400 mt-0.5">{t.vehicleType}</p>
                  </div>
                  <div className="flex items-center gap-1 ml-2 shrink-0">
                    <button onClick={e => { e.stopPropagation(); toggleActive(t); }}
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

        {/* Template constructor */}
        <div className="md:col-span-2">
          {!selected ? (
            <div className="bg-white border rounded-xl p-8 text-center text-gray-400 text-sm">
              Выберите шаблон для редактирования
            </div>
          ) : (
            <div className="bg-white border rounded-xl shadow-sm overflow-hidden">
              <div className="px-4 py-3 border-b bg-gray-50 flex justify-between items-center">
                <div>
                  <span className="font-semibold">{selected.name}</span>
                  <span className="text-xs text-gray-400 ml-2">{selected.vehicleType}</span>
                  <span className={`ml-2 text-xs px-2 py-0.5 rounded ${selected.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                    {selected.isActive ? 'Активен' : 'Неактивен'}
                  </span>
                </div>
                <button onClick={() => setShowSectionForm(v => !v)}
                  className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded hover:bg-blue-700">+ Раздел</button>
              </div>

              {showSectionForm && (
                <div className="p-3 border-b flex gap-2 bg-blue-50">
                  <input value={sectionName} onChange={e => setSectionName(e.target.value)}
                    placeholder="Название раздела" className="flex-1 border rounded px-2 py-1.5 text-sm" />
                  <button onClick={addSection} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded">Добавить</button>
                  <button onClick={() => setShowSectionForm(false)} className="text-xs border px-3 py-1.5 rounded">✕</button>
                </div>
              )}

              <div className="divide-y max-h-[550px] overflow-y-auto">
                {(!selected.groups || selected.groups.length === 0) && (
                  <p className="text-center text-gray-400 py-8 text-sm">Нет разделов. Добавьте первый раздел.</p>
                )}

                {(selected.groups || []).map((section, sIdx) => (
                  <div key={section.id} className="p-4">
                    <div className="flex justify-between items-center mb-3">
                      <div className="flex items-center gap-2">
                        <span className="w-5 h-5 bg-blue-100 text-blue-600 rounded text-xs flex items-center justify-center font-bold">{sIdx + 1}</span>
                        <span className="font-medium text-sm">{section.name}</span>
                        <span className="text-xs text-gray-400">{section.items?.length || 0} полей</span>
                      </div>
                      <div className="flex gap-2">
                        <button onClick={() => setActiveSection(activeSection === section.id ? null : section.id)}
                          className="text-xs bg-blue-50 text-blue-600 px-2.5 py-1 rounded hover:bg-blue-100">+ Поле</button>
                        <button onClick={() => deleteSection(section.id)}
                          className="text-xs bg-red-50 text-red-500 px-2 py-1 rounded hover:bg-red-100">Удалить</button>
                      </div>
                    </div>

                    {/* Add field form */}
                    {activeSection === section.id && (
                      <div className="bg-gray-50 rounded-xl p-3 mb-3 space-y-3 border border-gray-200">
                        <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Новое поле</p>
                        <input value={itemTitle} onChange={e => setItemTitle(e.target.value)}
                          placeholder="Название поля *" className="w-full border rounded-lg px-2 py-1.5 text-sm" />

                        {/* Field type selector */}
                        <div>
                          <p className="text-xs text-gray-500 mb-1.5">Тип поля</p>
                          <div className="grid grid-cols-3 gap-1.5">
                            {FIELD_TYPES.map(ft => (
                              <button key={ft.value} onClick={() => setItemFieldType(ft.value)}
                                className={`flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-xs border transition-colors ${
                                  itemFieldType === ft.value
                                    ? 'bg-blue-600 text-white border-blue-600'
                                    : 'bg-white text-gray-600 border-gray-200 hover:border-blue-300'
                                }`}>
                                <span>{ft.icon}</span>
                                <span>{ft.label}</span>
                              </button>
                            ))}
                          </div>
                        </div>

                        {/* Description / options for SELECT */}
                        {itemFieldType === 'SELECT' ? (
                          <div>
                            <p className="text-xs text-gray-500 mb-1">Варианты ответа (каждый с новой строки)</p>
                            <textarea value={itemDesc} onChange={e => setItemDesc(e.target.value)}
                              placeholder="Вариант 1&#10;Вариант 2&#10;Вариант 3" rows={3}
                              className="w-full border rounded-lg px-2 py-1.5 text-sm resize-none" />
                          </div>
                        ) : (
                          <input value={itemDesc} onChange={e => setItemDesc(e.target.value)}
                            placeholder="Описание / подсказка (необязательно)"
                            className="w-full border rounded-lg px-2 py-1.5 text-sm" />
                        )}

                        <label className="flex items-center gap-2 text-xs cursor-pointer">
                          <input type="checkbox" checked={itemRequired} onChange={e => setItemRequired(e.target.checked)} />
                          <span className="text-gray-600">Обязательное поле</span>
                        </label>

                        <div className="flex gap-2">
                          <button onClick={() => addField(section.id)}
                            className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg hover:bg-blue-700">Добавить поле</button>
                          <button onClick={() => setActiveSection(null)}
                            className="text-xs border px-3 py-1.5 rounded-lg text-gray-600">Отмена</button>
                        </div>
                      </div>
                    )}

                    {/* Fields list */}
                    <div className="space-y-1.5">
                      {(!section.items || section.items.length === 0) && (
                        <p className="text-xs text-gray-400 text-center py-2">Нет полей в разделе</p>
                      )}
                      {(section.items || []).map((field, fIdx) => {
                        const ft = ftMeta(field.fieldType || 'TEXT');
                        return (
                          <div key={field.id}
                            className="flex justify-between items-center bg-gray-50 rounded-lg px-3 py-2 text-sm border border-gray-100">
                            <div className="flex items-center gap-2 min-w-0">
                              <span className="text-gray-400 text-xs w-5 text-right shrink-0">{fIdx + 1}.</span>
                              <span className="text-xs px-1.5 py-0.5 bg-white border rounded text-gray-500 shrink-0 font-mono">{ft.icon}</span>
                              <div className="min-w-0">
                                <span className="text-sm">{field.title}</span>
                                {field.isRequired && <span className="text-red-500 ml-1">*</span>}
                                {field.description && <p className="text-xs text-gray-400 truncate mt-0.5">{field.description}</p>}
                              </div>
                            </div>
                            <div className="flex items-center gap-2 ml-3 shrink-0">
                              <span className="text-xs text-gray-400 hidden sm:block">{ft.label}</span>
                              <button onClick={() => deleteField(field.id)}
                                className="text-red-400 hover:text-red-600 text-xs px-1">✕</button>
                            </div>
                          </div>
                        );
                      })}
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
