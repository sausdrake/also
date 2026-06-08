import { useState, useEffect } from 'react';
import { Routes, Route, NavLink, useNavigate } from 'react-router-dom';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';
import { useDepositStore } from '../store/depositStore';
import { parseDate } from '../utils/formatDate';
import { formatDateTime } from '../utils/formatDate';

interface WatchedAuction {
  auctionId: number;
  vehicleInfo: string;
  currentPrice: number;
  startingPrice: number;
  status: string;
  endTime: string;
  mainPhotoUrl?: string;
}

// ─────────────────── Deposit Tab ───────────────────

const MAX_DEPOSIT = 10_000_000;

function DepositTab() {
  const { balance, blockedAmount, availableBalance, isLoading, fetchDeposit } = useDepositStore();
  const [transactions, setTransactions] = useState<any[]>([]);
  const [topUpAmount, setTopUpAmount] = useState('');
  const [withdrawAmount, setWithdrawAmount] = useState('');
  const [msg, setMsg] = useState<{ text: string; ok: boolean }>({ text: '', ok: true });

  const reloadTransactions = () => api.get('/deposits/my/transactions').then(r => setTransactions(r.data)).catch(() => {});

  useEffect(() => {
    fetchDeposit();
    reloadTransactions();
  }, []);

  const showMsg = (text: string, ok = true) => setMsg({ text, ok });

  const validateAmount = (raw: string, max: number): string | null => {
    const val = parseFloat(raw);
    if (!raw.trim() || isNaN(val)) return 'Введите сумму';
    if (val <= 0) return 'Сумма должна быть больше 0';
    if (val > max) return `Максимум: ${max.toLocaleString('ru')} сом`;
    return null;
  };

  const handleTopUp = async () => {
    const err = validateAmount(topUpAmount, MAX_DEPOSIT);
    if (err) { showMsg(err, false); return; }
    try {
      await api.post('/deposits/deposit', { amount: parseFloat(topUpAmount) });
      showMsg('Депозит пополнен');
      setTopUpAmount('');
      fetchDeposit();
      reloadTransactions();
    } catch (e: any) {
      showMsg(e.response?.data?.message || 'Ошибка', false);
    }
  };

  const handleWithdraw = async () => {
    const avail = availableBalance ?? 0;
    const err = validateAmount(withdrawAmount, avail);
    if (err) { showMsg(err, false); return; }
    try {
      await api.post('/deposits/withdraw', { amount: parseFloat(withdrawAmount) });
      showMsg('Средства выведены');
      setWithdrawAmount('');
      fetchDeposit();
      reloadTransactions();
    } catch (e: any) {
      showMsg(e.response?.data?.message || 'Ошибка', false);
    }
  };

  if (isLoading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-6">
      {msg.text && (
        <div className={`rounded p-3 border text-sm ${msg.ok ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-700'}`}>
          {msg.text}
        </div>
      )}

      {/* Баланс */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {[
          { label: 'Общий баланс', value: balance },
          { label: 'Заблокировано', value: blockedAmount },
          { label: 'Доступно', value: availableBalance },
        ].map(({ label, value }) => (
          <div key={label} className="bg-white border rounded-xl p-4 shadow-sm">
            <p className="text-sm text-gray-500">{label}</p>
            <p className="text-2xl font-bold text-gray-900 mt-1">
              {value != null ? `${Number(value).toLocaleString('ru')} сом` : '—'}
            </p>
          </div>
        ))}
      </div>

      {/* Операции */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-white border rounded-xl p-4 shadow-sm">
          <h3 className="font-semibold mb-3">Пополнить</h3>
          <div className="flex gap-2 mb-2">
            {[500, 1000, 5000, 10000].map(v => (
              <button key={v} onClick={() => setTopUpAmount(String(v))}
                className="px-3 py-1 text-sm border rounded-lg hover:bg-blue-50">
                {v.toLocaleString('ru')}
              </button>
            ))}
          </div>
          <div className="flex gap-2">
            <input type="number" value={topUpAmount} onChange={e => setTopUpAmount(e.target.value)}
              placeholder="Сумма" className="flex-1 border rounded-lg px-3 py-2" />
            <button onClick={handleTopUp} className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700">
              Пополнить
            </button>
          </div>
        </div>

        <div className="bg-white border rounded-xl p-4 shadow-sm">
          <h3 className="font-semibold mb-3">Вывести</h3>
          <div className="flex gap-2">
            <input type="number" value={withdrawAmount} onChange={e => setWithdrawAmount(e.target.value)}
              placeholder="Сумма" className="flex-1 border rounded-lg px-3 py-2" />
            <button onClick={handleWithdraw} className="bg-gray-700 text-white px-4 py-2 rounded-lg hover:bg-gray-800">
              Вывести
            </button>
          </div>
          <p className="text-xs text-gray-400 mt-1">Доступно: {availableBalance?.toLocaleString('ru') ?? '—'} сом</p>
        </div>
      </div>

      {/* История транзакций */}
      <div className="bg-white border rounded-xl shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b bg-gray-50">
          <h3 className="font-semibold">История транзакций</h3>
        </div>
        {transactions.length === 0 ? (
          <p className="text-center text-gray-400 py-8">Нет транзакций</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50 text-gray-500">
                <tr>
                  {['Дата', 'Тип', 'Сумма', 'Описание'].map(h => (
                    <th key={h} className="px-4 py-2 text-left font-medium">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {transactions.map((tx: any) => (
                  <tr key={tx.id} className="border-t hover:bg-gray-50">
                    <td className="px-4 py-2 text-gray-500 whitespace-nowrap">
                      {formatDateTime(tx.createdAt)}
                    </td>
                    <td className="px-4 py-2">
                      <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                        tx.type === 'DEPOSIT' ? 'bg-green-100 text-green-700' :
                        tx.type === 'WITHDRAWAL' ? 'bg-red-100 text-red-700' :
                        tx.type === 'BLOCK' ? 'bg-yellow-100 text-yellow-700' :
                        'bg-gray-100 text-gray-600'
                      }`}>{tx.type}</span>
                    </td>
                    <td className="px-4 py-2 font-medium">
                      {Number(tx.amount).toLocaleString('ru')} сом
                    </td>
                    <td className="px-4 py-2 text-gray-500">{tx.description || '—'}</td>
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

// ─────────────────── Shared helpers ───────────────────

const STATUS_STYLE: Record<string, string> = {
  ACTIVE:    'bg-green-100 text-green-700',
  COMPLETED: 'bg-blue-100 text-blue-700',
  SOLD:      'bg-purple-100 text-purple-700',
  CANCELLED: 'bg-gray-100 text-gray-500',
  CREATED:   'bg-yellow-100 text-yellow-700',
};

const STATUS_LABEL: Record<string, string> = {
  ACTIVE:    'Активен',
  COMPLETED: 'Завершён',
  SOLD:      'Продан',
  CANCELLED: 'Отменён',
  CREATED:   'Ожидает',
};

function fmt(val: any) {
  return val != null ? `${Number(val).toLocaleString('ru')} сом` : '—';
}

// ─────────────────── Bids Tab (участвовал) ───────────────────

function BidsTab() {
  const [auctions, setAuctions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get('/auctions/participated')
      .then(r => setAuctions(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <p className="text-gray-500 text-sm">Аукционы, в которых вы делали ставки</p>
      {auctions.length === 0 ? (
        <div className="bg-white border rounded-xl p-8 text-center text-gray-400">
          Вы ещё не участвовали ни в одном аукционе
        </div>
      ) : (
        <div className="grid gap-3">
          {auctions.map((a: any) => (
            <div key={a.id} onClick={() => navigate(`/auction/${a.id}`)}
              className="bg-white border rounded-xl p-4 flex justify-between items-center cursor-pointer hover:shadow-md transition-shadow">
              <div>
                <p className="font-medium">{a.vehicleInfo || `Аукцион #${a.id}`}</p>
                <p className="text-sm text-gray-500">
                  Текущая цена: {fmt(a.currentPrice)}
                  {a.endTime && <span className="ml-3 text-gray-400">· {formatDateTime(a.endTime)}</span>}
                </p>
              </div>
              <span className={`px-3 py-1 rounded-full text-xs font-medium ${STATUS_STYLE[a.status] ?? 'bg-gray-100 text-gray-600'}`}>
                {STATUS_LABEL[a.status] ?? a.status}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ─────────────────── Won Tab (выигранные) ───────────────────

function WonTab() {
  const [auctions, setAuctions] = useState<any[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const load = (p: number) => {
    setLoading(true);
    api.get(`/auctions/won?page=${p}&size=20`)
      .then(r => {
        setAuctions(r.data.content || []);
        setTotalPages(r.data.totalPages ?? 0);
        setPage(p);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(0); }, []);

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <p className="text-gray-500 text-sm">Аукционы, которые вы выиграли</p>
      {auctions.length === 0 ? (
        <div className="bg-white border rounded-xl p-8 text-center text-gray-400">
          Вы ещё не выиграли ни одного аукциона
        </div>
      ) : (
        <>
          <div className="grid gap-3">
            {auctions.map((a: any) => (
              <div key={a.id} onClick={() => navigate(`/auction/${a.id}`)}
                className="bg-white border rounded-xl p-4 cursor-pointer hover:shadow-md transition-shadow">
                <div className="flex justify-between items-start">
                  <div>
                    <p className="font-semibold">{a.vehicleInfo || `Аукцион #${a.id}`}</p>
                    <p className="text-sm text-gray-500 mt-0.5">
                      Выигрышная ставка: <span className="font-medium text-green-700">{fmt(a.winningBid ?? a.currentPrice)}</span>
                    </p>
                    {a.endTime && (
                      <p className="text-xs text-gray-400 mt-0.5">Завершён: {formatDateTime(a.endTime)}</p>
                    )}
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    <span className="px-3 py-1 rounded-full text-xs font-medium bg-green-100 text-green-700">
                      Победитель
                    </span>
                    <span className={`px-2 py-0.5 rounded text-xs ${STATUS_STYLE[a.status] ?? 'bg-gray-100 text-gray-600'}`}>
                      {STATUS_LABEL[a.status] ?? a.status}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>

          {totalPages > 1 && (
            <div className="flex justify-center gap-2 pt-2">
              <button disabled={page === 0} onClick={() => load(page - 1)}
                className="px-3 py-1 rounded border text-sm disabled:opacity-40 hover:bg-gray-50">
                ← Назад
              </button>
              <span className="px-3 py-1 text-sm text-gray-500">{page + 1} / {totalPages}</span>
              <button disabled={page + 1 >= totalPages} onClick={() => load(page + 1)}
                className="px-3 py-1 rounded border text-sm disabled:opacity-40 hover:bg-gray-50">
                Вперёд →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ─────────────────── Requests Tab ───────────────────

const VIN_REGEX = /^[A-HJ-NPR-Z0-9]{17}$/i;

function RequestsTab() {
  const [requests, setRequests] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({ brand: '', model: '', year: '', vin: '' });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState('');

  const loadRequests = () => {
    api.get('/diagnostic/requests/my?page=0&size=20')
      .then(r => setRequests(r.data.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { loadRequests(); }, []);

  const validateForm = () => {
    const errs: Record<string, string> = {};
    if (!form.brand.trim()) errs.brand = 'Введите марку';
    if (!form.model.trim()) errs.model = 'Введите модель';
    const yr = parseInt(form.year);
    if (!form.year || isNaN(yr) || yr < 1900 || yr > new Date().getFullYear() + 1) {
      errs.year = 'Введите корректный год (1900–текущий)';
    }
    if (!VIN_REGEX.test(form.vin.trim())) {
      errs.vin = 'VIN должен содержать ровно 17 символов (A-Z, 0-9, без I, O, Q)';
    }
    return errs;
  };

  const handleCreate = async () => {
    const errs = validateForm();
    if (Object.keys(errs).length > 0) { setFormErrors(errs); return; }
    setFormErrors({});
    try {
      await api.post('/diagnostic/requests', {
        brand: form.brand.trim(),
        model: form.model.trim(),
        year: parseInt(form.year),
        vin: form.vin.trim().toUpperCase(),
      });
      setMsg('Заявка создана');
      setShowForm(false);
      setForm({ brand: '', model: '', year: '', vin: '' });
      loadRequests();
    } catch (e: any) {
      setMsg(e.response?.data?.message || 'Ошибка создания заявки');
    }
  };

  const handleCancel = async (id: number) => {
    try {
      await api.delete(`/diagnostic/requests/${id}`);
      setMsg('Заявка отозвана');
      loadRequests();
    } catch (e: any) {
      setMsg(e.response?.data?.message || 'Ошибка');
    }
  };

  const statusLabels: Record<string, { label: string; color: string }> = {
    PENDING: { label: 'На рассмотрении', color: 'bg-yellow-100 text-yellow-700' },
    IN_PROGRESS: { label: 'В работе', color: 'bg-blue-100 text-blue-700' },
    COMPLETED: { label: 'На проверке', color: 'bg-purple-100 text-purple-700' },
    APPROVED: { label: 'Одобрено', color: 'bg-green-100 text-green-700' },
    REJECTED: { label: 'Отклонено', color: 'bg-red-100 text-red-700' },
  };

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      {msg && <div className="bg-blue-50 border border-blue-200 text-blue-800 rounded p-3">{msg}</div>}

      <div className="flex justify-between items-center">
        <h3 className="font-semibold text-gray-700">Мои заявки на диагностику</h3>
        <button onClick={() => setShowForm(!showForm)}
          className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700 text-sm">
          + Подать заявку
        </button>
      </div>

      {showForm && (
        <div className="bg-white border rounded-xl p-4 shadow-sm space-y-3">
          <h4 className="font-medium">Новая заявка</h4>
          <div className="grid grid-cols-2 gap-3">
            {[
              { field: 'brand', placeholder: 'Марка (BMW, Toyota...)' },
              { field: 'model', placeholder: 'Модель (X5, Camry...)' },
              { field: 'year', placeholder: 'Год выпуска (напр. 2020)' },
              { field: 'vin', placeholder: 'VIN — 17 символов' },
            ].map(({ field, placeholder }) => (
              <div key={field}>
                <input type="text" placeholder={placeholder}
                  value={(form as any)[field]}
                  onChange={e => { setForm({ ...form, [field]: e.target.value }); setFormErrors(p => ({ ...p, [field]: '' })); }}
                  className={`w-full border rounded-lg px-3 py-2 text-sm ${(formErrors as any)[field] ? 'border-red-400' : ''}`} />
                {(formErrors as any)[field] && <p className="text-red-500 text-xs mt-0.5">{(formErrors as any)[field]}</p>}
              </div>
            ))}
          </div>
          <div className="flex gap-2">
            <button onClick={handleCreate}
              className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700 text-sm">
              Создать
            </button>
            <button onClick={() => { setShowForm(false); setFormErrors({}); }}
              className="border px-4 py-2 rounded-lg hover:bg-gray-50 text-sm">
              Отмена
            </button>
          </div>
        </div>
      )}

      {requests.length === 0 ? (
        <div className="bg-white border rounded-xl p-8 text-center text-gray-400">
          Нет заявок на диагностику
        </div>
      ) : (
        <div className="grid gap-3">
          {requests.map((req: any) => {
            const statusInfo = statusLabels[req.status] || { label: req.status, color: 'bg-gray-100 text-gray-600' };
            return (
              <div key={req.id} className="bg-white border rounded-xl p-4 flex justify-between items-center">
                <div>
                  <p className="font-medium">{req.brand} {req.model} {req.year}</p>
                  <p className="text-sm text-gray-500">VIN: {req.vin}</p>
                </div>
                <div className="flex items-center gap-3">
                  <span className={`px-3 py-1 rounded-full text-xs font-medium ${statusInfo.color}`}>
                    {statusInfo.label}
                  </span>
                  {req.status === 'PENDING' && (
                    <button onClick={() => handleCancel(req.id)}
                      className="text-red-500 hover:text-red-700 text-sm border border-red-200 px-3 py-1 rounded-lg">
                      Отозвать
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

// ─────────────────── Watched Tab (отслеживаемые) ───────────────────

function useCountdown(targetIso: string | null) {
  const [text, setText] = useState('');
  useEffect(() => {
    if (!targetIso) return;
    const tick = () => {
      const diff = (parseDate(targetIso)?.getTime() ?? 0) - Date.now();
      if (diff <= 0) { setText('Завершён'); return; }
      const d = Math.floor(diff / 86_400_000);
      const h = Math.floor((diff % 86_400_000) / 3_600_000);
      const m = Math.floor((diff % 3_600_000) / 60_000);
      const s = Math.floor((diff % 60_000) / 1000);
      if (d > 0) setText(`${d} дн. ${h} ч.`);
      else if (h > 0) setText(`${h} ч. ${m} мин. ${s} с`);
      else setText(`${m} мин. ${s} с`);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [targetIso]);
  return text;
}

interface WatchedCardProps {
  a: WatchedAuction;
  onUnsubscribe: (id: number) => void;
  onNavigate: (id: number) => void;
}

function WatchedCard({ a, onUnsubscribe, onNavigate }: WatchedCardProps) {
  const countdown = useCountdown(a.status === 'ACTIVE' ? a.endTime : null);

  const statusColor = (status: string) => {
    if (status === 'ACTIVE') return 'bg-green-100 text-green-700';
    if (status === 'CREATED') return 'bg-blue-100 text-blue-700';
    return 'bg-gray-100 text-gray-600';
  };

  const statusLabel = (status: string) => {
    if (status === 'ACTIVE') return 'Активен';
    if (status === 'CREATED') return 'Создан';
    if (status === 'COMPLETED') return 'Завершён';
    if (status === 'EXPIRED') return 'Истёк';
    if (status === 'CANCELLED') return 'Отменён';
    return status;
  };

  return (
    <div className="bg-white border rounded-xl overflow-hidden shadow-sm hover:shadow-md transition-shadow relative">
      <button
        onClick={() => onUnsubscribe(a.auctionId)}
        className="absolute top-2 right-2 z-10 w-6 h-6 bg-white/80 rounded-full text-gray-400 hover:text-red-500 hover:bg-red-50 flex items-center justify-center text-xs font-bold border"
        title="Отписаться"
      >
        ✕
      </button>
      {a.mainPhotoUrl ? (
        <img src={a.mainPhotoUrl} alt="" className="w-full h-32 object-cover" />
      ) : (
        <div className="w-full h-32 bg-gray-100 flex items-center justify-center text-gray-300">
          <svg className="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1}
              d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
        </div>
      )}
      <div className="p-3 space-y-2">
        <div className="flex items-start justify-between gap-2">
          <p className="font-medium text-sm text-gray-900 leading-tight line-clamp-2">{a.vehicleInfo || `Лот #${a.auctionId}`}</p>
          <span className={`shrink-0 px-2 py-0.5 rounded-full text-xs font-medium ${statusColor(a.status)}`}>
            {statusLabel(a.status)}
          </span>
        </div>
        <div>
          <p className="text-xs text-gray-400">Текущая цена</p>
          <p className="font-bold text-blue-600">{Number(a.currentPrice).toLocaleString('ru')} сом</p>
        </div>
        {a.status === 'ACTIVE' && countdown && (
          <p className="text-xs font-medium text-orange-600">Осталось: {countdown}</p>
        )}
        <button
          onClick={() => onNavigate(a.auctionId)}
          className="w-full py-1.5 bg-blue-600 text-white rounded-lg text-xs font-medium hover:bg-blue-700 transition-colors"
        >
          Перейти к лоту
        </button>
      </div>
    </div>
  );
}

function WatchedTab() {
  const [watchedAuctions, setWatchedAuctions] = useState<WatchedAuction[]>([]);
  const [watchSearch, setWatchSearch] = useState('');
  const [watchSort, setWatchSort] = useState<'price_asc' | 'price_desc' | 'time_asc' | 'time_desc'>('time_asc');
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const loadWatched = () => {
    api.get('/subscriptions').then(r => setWatchedAuctions(r.data)).catch(() => {}).finally(() => setLoading(false));
  };

  useEffect(() => { loadWatched(); }, []);

  const handleUnsubscribe = async (auctionId: number) => {
    try {
      await api.delete(`/subscriptions/${auctionId}`);
      setWatchedAuctions(prev => prev.filter(a => a.auctionId !== auctionId));
    } catch {}
  };

  const filtered = watchedAuctions
    .filter(a => !watchSearch || a.vehicleInfo?.toLowerCase().includes(watchSearch.toLowerCase()))
    .sort((a, b) => {
      if (watchSort === 'price_asc') return a.currentPrice - b.currentPrice;
      if (watchSort === 'price_desc') return b.currentPrice - a.currentPrice;
      if (watchSort === 'time_asc') return new Date(a.endTime).getTime() - new Date(b.endTime).getTime();
      if (watchSort === 'time_desc') return new Date(b.endTime).getTime() - new Date(a.endTime).getTime();
      return 0;
    });

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row gap-3">
        <input
          type="text"
          value={watchSearch}
          onChange={e => setWatchSearch(e.target.value)}
          placeholder="Поиск по названию..."
          className="flex-1 border rounded-lg px-3 py-2 text-sm"
        />
        <select
          value={watchSort}
          onChange={e => setWatchSort(e.target.value as typeof watchSort)}
          className="border rounded-lg px-3 py-2 text-sm"
        >
          <option value="time_asc">По времени (сначала ранние)</option>
          <option value="time_desc">По времени (сначала поздние)</option>
          <option value="price_asc">По цене (сначала дешёвые)</option>
          <option value="price_desc">По цене (сначала дорогие)</option>
        </select>
      </div>

      {filtered.length === 0 ? (
        <div className="bg-white border rounded-xl p-8 text-center text-gray-400">
          {watchSearch ? 'Ничего не найдено' : 'Вы не следите ни за одним лотом'}
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {filtered.map(a => (
            <WatchedCard
              key={a.auctionId}
              a={a}
              onUnsubscribe={handleUnsubscribe}
              onNavigate={id => navigate(`/auction/${id}`)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ─────────────────── Profile Tab ───────────────────

function ProfileTab() {
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="bg-white border rounded-xl p-6 shadow-sm max-w-md">
      <h3 className="font-semibold text-lg mb-4">Профиль</h3>
      <div className="space-y-3">
        <div>
          <p className="text-sm text-gray-500">Имя пользователя</p>
          <p className="font-medium">{user?.username ?? '—'}</p>
        </div>
        <div>
          <p className="text-sm text-gray-500">Email</p>
          <p className="font-medium">{user?.email ?? '—'}</p>
        </div>
        <div>
          <p className="text-sm text-gray-500">Роли</p>
          <div className="flex gap-2 mt-1">
            {user?.roles?.map(r => (
              <span key={r} className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs">{r}</span>
            ))}
          </div>
        </div>
      </div>
      <button onClick={handleLogout}
        className="mt-6 w-full bg-red-50 text-red-600 border border-red-200 py-2 rounded-lg hover:bg-red-100">
        Выйти
      </button>
    </div>
  );
}

// ─────────────────── Main ProfilePage ───────────────────

// USER: депозит, ставки, выигранные, заявки, отслеживаемые, профиль
const USER_TABS = [
  { path: '', label: 'Депозит' },
  { path: 'bids', label: 'Участвовал' },
  { path: 'won', label: 'Выигранные' },
  { path: 'requests', label: 'Заявки' },
  { path: 'watched', label: 'Отслеживаемые' },
  { path: 'settings', label: 'Профиль' },
];

// DIAGNOSTIC: только профиль (торги и депозит не нужны — они работают через /diagnostic)
const DIAGNOSTIC_TABS = [
  { path: '', label: 'Профиль' },
];

export function ProfilePage() {
  const { user } = useAuthStore();
  const isDiagnostic = user?.roles?.some(r => r === 'ROLE_DIAGNOSTIC' || r === 'DIAGNOSTIC');
  const tabs = isDiagnostic ? DIAGNOSTIC_TABS : USER_TABS;

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">
        {isDiagnostic ? 'Профиль диагностика' : 'Личный кабинет'}
      </h1>

      {/* Tabs */}
      <div className="flex gap-1 bg-gray-100 p-1 rounded-xl mb-6 overflow-x-auto">
        {tabs.map(({ path, label }) => (
          <NavLink
            key={path}
            to={path === '' ? '/profile' : `/profile/${path}`}
            end={path === ''}
            className={({ isActive }) =>
              `flex-1 text-center py-2 px-3 rounded-lg text-sm font-medium transition-colors whitespace-nowrap ${
                isActive ? 'bg-white text-blue-600 shadow-sm' : 'text-gray-600 hover:text-gray-900'
              }`
            }
          >
            {label}
          </NavLink>
        ))}
      </div>

      {/* Content */}
      <Routes>
        {isDiagnostic ? (
          <Route index element={<ProfileTab />} />
        ) : (
          <>
            <Route index element={<DepositTab />} />
            <Route path="bids" element={<BidsTab />} />
            <Route path="won" element={<WonTab />} />
            <Route path="requests" element={<RequestsTab />} />
            <Route path="watched" element={<WatchedTab />} />
            <Route path="settings" element={<ProfileTab />} />
          </>
        )}
      </Routes>
    </div>
  );
}
