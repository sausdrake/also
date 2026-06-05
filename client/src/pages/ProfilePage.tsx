import { useState, useEffect } from 'react';
import { Routes, Route, NavLink, useNavigate } from 'react-router-dom';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';
import { useDepositStore } from '../store/depositStore';
import { formatDateTime } from '../utils/formatDate';

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

// ─────────────────── Bids Tab ───────────────────

function BidsTab() {
  const [auctions, setAuctions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get('/public/auctions?page=0&size=50')
      .then(r => setAuctions(r.data.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <p className="text-gray-500 text-sm">Аукционы, в которых вы участвовали</p>
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
                <p className="text-sm text-gray-500">Текущая цена: {Number(a.currentPrice).toLocaleString('ru')} сом</p>
              </div>
              <span className={`px-3 py-1 rounded-full text-xs font-medium ${
                a.status === 'ACTIVE' ? 'bg-green-100 text-green-700' :
                a.status === 'COMPLETED' ? 'bg-blue-100 text-blue-700' :
                a.status === 'SOLD' ? 'bg-purple-100 text-purple-700' :
                'bg-gray-100 text-gray-600'
              }`}>{a.status}</span>
            </div>
          ))}
        </div>
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

// USER: депозит, ставки, заявки на диагностику, профиль
const USER_TABS = [
  { path: '', label: 'Депозит' },
  { path: 'bids', label: 'Мои ставки' },
  { path: 'requests', label: 'Заявки' },
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
            <Route path="requests" element={<RequestsTab />} />
            <Route path="settings" element={<ProfileTab />} />
          </>
        )}
      </Routes>
    </div>
  );
}
