import { useState, useEffect, useRef } from 'react';
import { Routes, Route, NavLink, useNavigate } from 'react-router-dom';
import api from '../api/client';
import * as subscriptions from '../api/subscriptions';
import type { WatchedAuction } from '../api/subscriptions';
import { apiErrorMessage, parseApiError } from '../api/errors';
import { KycRequiredNotice } from '../components/identity/KycRequiredNotice';
import { LoadErrorAlert } from '../components/LoadErrorAlert';
import { AUCTION_STATUS_LABELS } from '../domain/auction';
import { labelOf, type Parsed } from '../domain/parse';
import { roleLabel } from '../domain/role';
import {
  WITHDRAWAL_OWNER_LABELS, parseWithdrawalStatus, toWithdrawalAmount, validateWithdrawalAmount, type WithdrawalStatus,
} from '../domain/withdrawal';
import { useAuthStore } from '../store/authStore';
import { useDepositStore } from '../store/depositStore';
import { parseDate } from '../utils/formatDate';
import { formatDateTime } from '../utils/formatDate';
import { depositTxTypeLabel } from '../utils/depositTransactionLabels';
import { RequestVehicleStatus } from '../components/seller/RequestVehicleStatus';
import { IdentityVerificationTab } from '../components/identity/IdentityVerificationTab';
import { TelegramLinkCard } from '../components/auth/TelegramLinkCard';

// ─────────────────── Deposit Tab ───────────────────

interface WithdrawalRequest {
  id: number;
  amount: number;
  status: Parsed<WithdrawalStatus>;
  createdAt: string;
  processedAt: string | null;
  rejectionReason: string | null;
}

// F6: a withdrawal is a manual payout request; PAID means AlSo paid by hand, not a bank transfer.
// FE-WD-01: labels come from the domain model (owner wording); only the colours live here.
const WITHDRAWAL_STYLE: Record<WithdrawalStatus, string> = {
  PENDING: 'bg-yellow-100 text-yellow-700',
  PROCESSING: 'bg-blue-100 text-blue-700',
  PAID: 'bg-green-100 text-green-700',
  REJECTED: 'bg-red-100 text-red-700',
};

/** A row of GET /withdrawals/my, or null when it cannot be shown truthfully (no id / amount). */
function parseOwnWithdrawal(raw: unknown): WithdrawalRequest | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const amount = typeof r.amount === 'number' ? r.amount : typeof r.amount === 'string' ? Number(r.amount) : NaN;
  if (typeof r.id !== 'number' || !Number.isFinite(amount)) return null;
  return {
    id: r.id,
    amount,
    status: parseWithdrawalStatus(r.status),
    createdAt: typeof r.createdAt === 'string' ? r.createdAt : '',
    processedAt: typeof r.processedAt === 'string' ? r.processedAt : null,
    rejectionReason: typeof r.rejectionReason === 'string' ? r.rejectionReason : null,
  };
}

function DepositTab() {
  const { balance, blockedAmount, availableBalance, isLoading, fetchDeposit } = useDepositStore();
  const [transactions, setTransactions] = useState<any[]>([]);
  const [withdrawAmount, setWithdrawAmount] = useState('');
  const [msg, setMsg] = useState<{ text: string; ok: boolean }>({ text: '', ok: true });
  // FE-KYC-01: 403 IDENTITY_VERIFICATION_REQUIRED from POST /withdrawals -> guidance, not a bare error.
  const [withdrawKyc, setWithdrawKyc] = useState<string | null>(null);
  // Stable idempotency key for the withdrawal currently being attempted: generated once
  // for a given amount and reused on retry (e.g. after a network error) so the backend's
  // idempotency check protects against double-withdrawal. Regenerated only when the amount
  // field changes, i.e. a genuinely new withdrawal attempt.
  const withdrawKeyRef = useRef<{ amount: string; key: string } | null>(null);
  const getWithdrawKey = (amount: string): string => {
    if (withdrawKeyRef.current?.amount !== amount) {
      withdrawKeyRef.current = { amount, key: crypto.randomUUID() };
    }
    return withdrawKeyRef.current.key;
  };

  const [withdrawals, setWithdrawals] = useState<WithdrawalRequest[]>([]);
  // FE-WD-01: a failed or malformed list is an error with a retry - never "no requests".
  const [withdrawalsError, setWithdrawalsError] = useState('');
  // One POST /withdrawals at a time (the idempotency key already protects the money; this protects the UX).
  const [withdrawing, setWithdrawing] = useState(false);
  const withdrawInFlight = useRef(false);

  const [transactionsError, setTransactionsError] = useState('');
  const reloadTransactions = () => api.get('/deposits/my/transactions')
    .then(r => { setTransactions(r.data); setTransactionsError(''); })
    .catch(e => setTransactionsError(apiErrorMessage(e, 'Не удалось загрузить историю транзакций')));
  const reloadWithdrawals = () =>
    api.get('/withdrawals/my')
      .then(r => {
        if (!Array.isArray(r.data)) throw new Error('Malformed withdrawals response');
        setWithdrawals(r.data.map(parseOwnWithdrawal).filter((w): w is WithdrawalRequest => w !== null));
        setWithdrawalsError('');
      })
      .catch(e => setWithdrawalsError(apiErrorMessage(e, 'Не удалось загрузить заявки на вывод')));

  useEffect(() => {
    fetchDeposit();
    reloadTransactions();
    reloadWithdrawals();
  }, []);

  const showMsg = (text: string, ok = true) => setMsg({ text, ok });

  const handleWithdraw = async () => {
    if (withdrawInFlight.current) return;
    const err = validateWithdrawalAmount(withdrawAmount, availableBalance ?? null);
    if (err) { showMsg(err, false); return; }
    setWithdrawKyc(null);
    withdrawInFlight.current = true;
    setWithdrawing(true);
    try {
      await api.post('/withdrawals',
        { amount: toWithdrawalAmount(withdrawAmount), idempotencyKey: getWithdrawKey(withdrawAmount) });
      showMsg('Заявка на вывод создана. Выплату выполнит администратор вручную.');
      setWithdrawAmount('');
      withdrawKeyRef.current = null;
      fetchDeposit();
      reloadTransactions();
      reloadWithdrawals();
    } catch (e) {
      const error = parseApiError(e, 'Не удалось создать заявку на вывод');
      if (error.kind === 'kyc_required') {
        setMsg({ text: '', ok: true });
        setWithdrawKyc(error.message);
      } else {
        showMsg(error.message, false);
      }
    } finally {
      withdrawInFlight.current = false;
      setWithdrawing(false);
    }
  };

  const nothingToWithdraw = availableBalance != null && availableBalance <= 0;

  if (isLoading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-6">
      {withdrawKyc !== null && <KycRequiredNotice action="вывести средства" message={withdrawKyc} />}
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
        {/* Self-service top-up is disabled on the server (POST /deposits/deposit always 409) and
            no payment provider is connected yet, so there is nothing to submit here - only how
            funding works today. */}
        <div className="bg-white border rounded-xl p-4 shadow-sm" data-testid="top-up-info">
          <h3 className="font-semibold mb-3">Пополнить</h3>
          <p className="text-sm text-gray-600">
            Самостоятельное пополнение депозита пока недоступно. Зачисление средств выполняется
            администратором после проверки платежа.
          </p>
        </div>

        <div className="bg-white border rounded-xl p-4 shadow-sm">
          <h3 className="font-semibold mb-3">Вывести</h3>
          <div className="flex gap-2">
            <input type="number" value={withdrawAmount} onChange={e => setWithdrawAmount(e.target.value)}
              placeholder="Сумма" className="flex-1 border rounded-lg px-3 py-2" />
            <button onClick={handleWithdraw} disabled={withdrawing || nothingToWithdraw}
              className="bg-gray-700 text-white px-4 py-2 rounded-lg hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed">
              {withdrawing ? 'Отправка…' : 'Вывести'}
            </button>
          </div>
          <p className="text-xs text-gray-400 mt-1">Доступно: {availableBalance?.toLocaleString('ru') ?? '—'} сом</p>
          {nothingToWithdraw && <p className="text-xs text-gray-500 mt-1">Нет доступных средств для вывода.</p>}
          <p className="text-xs text-gray-500 mt-1">
            Вывод оформляется заявкой: сумма сразу списывается с доступного баланса, выплату выполняет
            администратор вручную. Если заявку отклонят, сумма вернётся на депозит.
          </p>
        </div>
      </div>

      {withdrawalsError && (
        <div role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700 space-y-2">
          <p>{withdrawalsError}</p>
          <button onClick={reloadWithdrawals} className="px-3 py-1.5 bg-white border border-red-300 rounded-lg text-xs font-medium">
            Повторить
          </button>
        </div>
      )}
      {!withdrawalsError && withdrawals.length > 0 && (
        <div className="bg-white border rounded-xl shadow-sm overflow-hidden" data-testid="my-withdrawals">
          <div className="px-4 py-3 border-b bg-gray-50">
            <h3 className="font-semibold">Заявки на вывод</h3>
          </div>
          <ul className="divide-y">
            {withdrawals.map(w => (
              <li key={w.id} className="px-4 py-2 text-sm flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="text-gray-500 whitespace-nowrap">{formatDateTime(w.createdAt)}</span>
                <span className="font-medium">{Number(w.amount).toLocaleString('ru')} сом</span>
                <span className={`px-2 py-0.5 rounded text-xs font-medium ${w.status.known ? WITHDRAWAL_STYLE[w.status.value] : 'bg-gray-100 text-gray-600'}`}>
                  {labelOf(WITHDRAWAL_OWNER_LABELS, w.status)}
                </span>
                {w.rejectionReason && <span className="text-red-600 basis-full">Причина: {w.rejectionReason}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* История транзакций */}
      <div className="bg-white border rounded-xl shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b bg-gray-50">
          <h3 className="font-semibold">История транзакций</h3>
        </div>
        {transactionsError ? (
          <div className="p-3"><LoadErrorAlert message={transactionsError} onRetry={reloadTransactions} /></div>
        ) : transactions.length === 0 ? (
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
                      }`}>{depositTxTypeLabel(tx.type)}</span>
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
  ACTIVE:               'bg-green-100 text-green-700',
  COMPLETED:            'bg-blue-100 text-blue-700',
  WAITING_FOR_PAYMENT:  'bg-amber-100 text-amber-700',
  SOLD:                 'bg-purple-100 text-purple-700',
  SALE_FAILED:          'bg-red-100 text-red-700',
  CANCELLED:            'bg-gray-100 text-gray-500',
  CREATED:              'bg-yellow-100 text-yellow-700',
};

const STATUS_LABEL: Record<string, string> = {
  ACTIVE:               'Активен',
  COMPLETED:            'Завершён',
  WAITING_FOR_PAYMENT:  'Ожидание оплаты',
  SOLD:                 'Продан',
  SALE_FAILED:          'Сделка не состоялась',
  CANCELLED:            'Отменён',
  CREATED:              'Ожидает',
};

function fmt(val: any) {
  return val != null ? `${Number(val).toLocaleString('ru')} сом` : '—';
}

// ─────────────────── Bids Tab (участвовал) ───────────────────

function BidsTab() {
  const [auctions, setAuctions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  const [error, setError] = useState('');

  // loading starts true; a retry sets it again before calling load (no setState inside the effect)
  const load = () => {
    api.get('/auctions/participated')
      .then(r => { setAuctions(r.data || []); setError(''); })
      .catch(e => setError(apiErrorMessage(e, 'Не удалось загрузить аукционы с вашим участием')))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <p className="text-gray-500 text-sm">Аукционы, в которых вы делали ставки</p>
      {error ? (
        <LoadErrorAlert message={error} onRetry={() => { setLoading(true); load(); }} />
      ) : auctions.length === 0 ? (
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
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const load = (p: number) => {
    setLoading(true);
    api.get(`/auctions/won?page=${p}&size=20`)
      .then(r => {
        setAuctions(r.data.content || []);
        setTotalPages(r.data.totalPages ?? 0);
        setPage(p);
        setError('');
      })
      .catch(e => setError(apiErrorMessage(e, 'Не удалось загрузить выигранные аукционы')))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(0); }, []);

  if (loading) return <div className="p-4">Загрузка...</div>;

  return (
    <div className="space-y-4">
      <p className="text-gray-500 text-sm">Аукционы, которые вы выиграли</p>
      {error ? (
        <LoadErrorAlert message={error} onRetry={() => load(page)} />
      ) : auctions.length === 0 ? (
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
  // FE-KYC-01: errors are red (they used to share the blue "Заявка создана" style); a KYC refusal
  // of POST /diagnostic/requests is guidance with a link to the identity tab.
  const [msg, setMsg] = useState<{ text: string; ok: boolean }>({ text: '', ok: true });
  const [createKyc, setCreateKyc] = useState<string | null>(null);
  // FE-INFLIGHT-01 (BL-21): one POST /diagnostic/requests at a time - a double tap used to send two,
  // and the second one's conflict replaced "Заявка создана".
  const createInFlight = useRef(false);
  const [creating, setCreating] = useState(false);
  // Scenario 1 step 4: "Просмотр" opens the request's details (incl. why it was rejected).
  const [viewedId, setViewedId] = useState<number | null>(null);

  const [requestsError, setRequestsError] = useState('');

  const loadRequests = () => {
    api.get('/diagnostic/requests/my?page=0&size=20')
      .then(r => { setRequests(r.data.content || []); setRequestsError(''); })
      .catch(e => setRequestsError(apiErrorMessage(e, 'Не удалось загрузить заявки на диагностику')))
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
    if (createInFlight.current) return;
    const errs = validateForm();
    if (Object.keys(errs).length > 0) { setFormErrors(errs); return; }
    setFormErrors({});
    setCreateKyc(null);
    createInFlight.current = true;
    setCreating(true);
    try {
      await api.post('/diagnostic/requests', {
        brand: form.brand.trim(),
        model: form.model.trim(),
        year: parseInt(form.year),
        vin: form.vin.trim().toUpperCase(),
      });
      setMsg({ text: 'Заявка создана', ok: true });
      setShowForm(false);
      setForm({ brand: '', model: '', year: '', vin: '' });
      loadRequests();
    } catch (e) {
      const error = parseApiError(e, 'Ошибка создания заявки');
      if (error.kind === 'kyc_required') {
        setMsg({ text: '', ok: true });
        setCreateKyc(error.message);
      } else {
        setMsg({ text: error.message, ok: false });
      }
    } finally {
      createInFlight.current = false;
      setCreating(false);
    }
  };

  const handleCancel = async (id: number) => {
    try {
      await api.delete(`/diagnostic/requests/${id}`);
      setMsg({ text: 'Заявка отозвана', ok: true });
      loadRequests();
    } catch (e) {
      setMsg({ text: apiErrorMessage(e, 'Не удалось отозвать заявку'), ok: false });
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
      {createKyc !== null && <KycRequiredNotice action="подать заявку на диагностику" message={createKyc} />}
      {msg.text && (
        <div role={msg.ok ? 'status' : 'alert'}
          className={`rounded p-3 border ${msg.ok ? 'bg-blue-50 border-blue-200 text-blue-800' : 'bg-red-50 border-red-200 text-red-700'}`}>
          {msg.text}
        </div>
      )}

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
            <button onClick={handleCreate} disabled={creating}
              className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700 text-sm disabled:opacity-50 disabled:cursor-not-allowed">
              {creating ? 'Отправка…' : 'Создать'}
            </button>
            <button onClick={() => { setShowForm(false); setFormErrors({}); }}
              className="border px-4 py-2 rounded-lg hover:bg-gray-50 text-sm">
              Отмена
            </button>
          </div>
        </div>
      )}

      {requestsError ? (
        <LoadErrorAlert message={requestsError} onRetry={loadRequests} />
      ) : requests.length === 0 ? (
        <div className="bg-white border rounded-xl p-8 text-center text-gray-400">
          Нет заявок на диагностику
        </div>
      ) : (
        <div className="grid gap-3">
          {requests.map((req: any) => {
            const statusInfo = statusLabels[req.status] || { label: req.status, color: 'bg-gray-100 text-gray-600' };
            return (
              <div key={req.id} className="bg-white border rounded-xl p-4">
                <div className="flex justify-between items-center">
                  <div>
                    <p className="font-medium">{req.brand} {req.model} {req.year}</p>
                    <p className="text-sm text-gray-500">VIN: {req.vin}</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`px-3 py-1 rounded-full text-xs font-medium ${statusInfo.color}`}>
                      {statusInfo.label}
                    </span>
                    <button onClick={() => setViewedId(viewedId === req.id ? null : req.id)}
                      aria-expanded={viewedId === req.id}
                      className="text-gray-600 hover:text-gray-800 text-sm border border-gray-200 px-3 py-1 rounded-lg">
                      {viewedId === req.id ? 'Скрыть' : 'Просмотр'}
                    </button>
                    {req.status === 'PENDING' && (
                      <button onClick={() => handleCancel(req.id)}
                        className="text-red-500 hover:text-red-700 text-sm border border-red-200 px-3 py-1 rounded-lg">
                        Отозвать
                      </button>
                    )}
                  </div>
                </div>
                {viewedId === req.id && (
                  <dl className="mt-3 pt-3 border-t grid grid-cols-2 gap-x-4 gap-y-1 text-sm" data-testid={`request-details-${req.id}`}>
                    <dt className="text-gray-400">Подана</dt><dd>{req.createdAt ? formatDateTime(req.createdAt) : '—'}</dd>
                    <dt className="text-gray-400">Обновлена</dt><dd>{req.updatedAt ? formatDateTime(req.updatedAt) : '—'}</dd>
                    <dt className="text-gray-400">Диагност</dt><dd>{req.diagnosticName || 'ещё не назначен'}</dd>
                    <dt className="text-gray-400">Залог</dt><dd>{req.depositFrozen != null ? `${Number(req.depositFrozen).toLocaleString('ru')} сом` : '—'}</dd>
                    {req.rejectedReason && (
                      <>
                        <dt className="text-gray-400">Причина отклонения</dt>
                        <dd className="text-red-600">{req.rejectedReason}</dd>
                      </>
                    )}
                  </dl>
                )}
                {req.status === 'APPROVED' && (
                  <RequestVehicleStatus vehicleId={req.vehicleId ?? null} />
                )}
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
  unsubscribing: boolean;
  onUnsubscribe: (id: number) => void;
  onNavigate: (id: number) => void;
}

function WatchedCard({ a, unsubscribing, onUnsubscribe, onNavigate }: WatchedCardProps) {
  const active = a.status.known && a.status.value === 'ACTIVE';
  const countdown = useCountdown(active ? a.endTime : null);

  // FE-SUB-01: labels come from the domain model (FE-STATE-01); an unknown status shows its raw value.
  const statusColor = active ? 'bg-green-100 text-green-700'
    : a.status.known && a.status.value === 'CREATED' ? 'bg-blue-100 text-blue-700'
    : 'bg-gray-100 text-gray-600';

  return (
    <div className="bg-white border rounded-xl overflow-hidden shadow-sm hover:shadow-md transition-shadow relative">
      <button
        onClick={() => onUnsubscribe(a.auctionId)}
        disabled={unsubscribing}
        className="absolute top-2 right-2 z-10 w-6 h-6 bg-white/80 rounded-full text-gray-400 hover:text-red-500 hover:bg-red-50 flex items-center justify-center text-xs font-bold border disabled:opacity-50"
        title="Отписаться"
        aria-label={`Отписаться от лота #${a.auctionId}`}
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
          <p className="font-medium text-sm text-gray-900 leading-tight line-clamp-2 break-words min-w-0">{a.vehicleInfo || `Лот #${a.auctionId}`}</p>
          <span className={`shrink-0 px-2 py-0.5 rounded-full text-xs font-medium ${statusColor}`}>
            {labelOf(AUCTION_STATUS_LABELS, a.status)}
          </span>
        </div>
        <div>
          <p className="text-xs text-gray-400">Текущая цена</p>
          <p className="font-bold text-blue-600">{a.currentPrice !== null ? `${a.currentPrice.toLocaleString('ru')} сом` : '—'}</p>
        </div>
        {active && countdown && (
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

type WatchedSort = 'price_asc' | 'price_desc' | 'time_asc' | 'time_desc';

/** Missing values (null price, absent or unparseable end time) always sort last, in either direction. */
function compareWatched(a: WatchedAuction, b: WatchedAuction, sort: WatchedSort): number {
  const key = (w: WatchedAuction) => sort.startsWith('price')
    ? w.currentPrice
    : w.endTime !== null ? Date.parse(w.endTime) : null;
  const ka = key(a);
  const kb = key(b);
  if (ka === null && kb === null) return 0;
  if (ka === null) return 1;
  if (kb === null) return -1;
  return sort.endsWith('asc') ? ka - kb : kb - ka;
}

type WatchedLoad =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ok'; items: WatchedAuction[] };

function WatchedTab() {
  // FE-SUB-01: an empty list is shown only after a successful [] - a failed or malformed answer is
  // an error with a retry, never "Вы не следите ни за одним лотом".
  const [load, setLoad] = useState<WatchedLoad>({ kind: 'loading' });
  const [watchSearch, setWatchSearch] = useState('');
  const [watchSort, setWatchSort] = useState<WatchedSort>('time_asc');
  const [unsubscribing, setUnsubscribing] = useState<Set<number>>(new Set());
  const [actionError, setActionError] = useState('');
  const navigate = useNavigate();

  const loadWatched = () => {
    setLoad({ kind: 'loading' });
    subscriptions.list()
      .then(items => setLoad({ kind: 'ok', items }))
      .catch(e => setLoad({ kind: 'error', message: apiErrorMessage(e, 'Не удалось загрузить отслеживаемые лоты') }));
  };

  useEffect(() => { loadWatched(); }, []);

  const handleUnsubscribe = async (auctionId: number) => {
    if (unsubscribing.has(auctionId)) return;
    setUnsubscribing(prev => new Set(prev).add(auctionId));
    setActionError('');
    try {
      await subscriptions.unsubscribe(auctionId);
      setLoad(prev => prev.kind === 'ok' ? { kind: 'ok', items: prev.items.filter(a => a.auctionId !== auctionId) } : prev);
    } catch (e) {
      setActionError(apiErrorMessage(e, 'Не удалось отписаться от лота'));
    } finally {
      setUnsubscribing(prev => { const s = new Set(prev); s.delete(auctionId); return s; });
    }
  };

  if (load.kind === 'loading') return <div className="p-4">Загрузка...</div>;

  if (load.kind === 'error') return (
    <div role="alert" className="bg-red-50 border border-red-200 rounded-xl p-4 text-sm text-red-700 space-y-3">
      <p>{load.message}</p>
      <button onClick={loadWatched}
        className="px-4 py-2 bg-white border border-red-300 rounded-lg text-red-700 font-medium hover:bg-red-100">
        Повторить
      </button>
    </div>
  );

  const query = watchSearch.toLowerCase();
  const filtered = load.items
    .filter(a => !watchSearch || (a.vehicleInfo ?? '').toLowerCase().includes(query))
    .sort((a, b) => compareWatched(a, b, watchSort));

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row gap-3">
        <input
          type="text"
          value={watchSearch}
          onChange={e => setWatchSearch(e.target.value)}
          placeholder="Поиск по названию..."
          className="flex-1 min-w-0 border rounded-lg px-3 py-2 text-sm"
        />
        <select
          value={watchSort}
          onChange={e => setWatchSort(e.target.value as WatchedSort)}
          aria-label="Сортировка"
          className="border rounded-lg px-3 py-2 text-sm"
        >
          <option value="time_asc">По времени (сначала ранние)</option>
          <option value="time_desc">По времени (сначала поздние)</option>
          <option value="price_asc">По цене (сначала дешёвые)</option>
          <option value="price_desc">По цене (сначала дорогие)</option>
        </select>
      </div>

      {actionError && (
        <div role="alert" className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">{actionError}</div>
      )}

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
              unsubscribing={unsubscribing.has(a.auctionId)}
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
  // AUTH-TG-UX-01: a Telegram-only account has no password to change. Unknown (request failed) keeps the
  // button - the backend refuses a passwordless change with a plain reason anyway.
  const [passwordSet, setPasswordSet] = useState<boolean | null>(null);
  useEffect(() => {
    let cancelled = false;
    api.get('/account/password')
      .then(r => { if (!cancelled && typeof r.data?.passwordSet === 'boolean') setPasswordSet(r.data.passwordSet); })
      .catch(() => { /* unknown: keep the default */ });
    return () => { cancelled = true; };
  }, []);

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
        <TelegramLinkCard />
        <div>
          <p className="text-sm text-gray-500">Роли</p>
          <div className="flex gap-2 mt-1">
            {user?.roles?.map(r => (
              <span key={r} className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs">{roleLabel(r)}</span>
            ))}
          </div>
        </div>
      </div>
      {passwordSet === false ? (
        <p className="mt-6 text-sm text-gray-500">Пароль не задан — вход выполняется через Telegram.</p>
      ) : (
        <button onClick={() => navigate('/change-password')}
          className="mt-6 w-full bg-gray-50 text-gray-700 border border-gray-200 py-2 rounded-lg hover:bg-gray-100">
          Сменить пароль
        </button>
      )}
      <button onClick={handleLogout}
        className="mt-3 w-full bg-red-50 text-red-600 border border-red-200 py-2 rounded-lg hover:bg-red-100">
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
  { path: 'identity', label: 'Подтверждение личности' },
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
            <Route path="identity" element={<IdentityVerificationTab />} />
            <Route path="settings" element={<ProfileTab />} />
          </>
        )}
      </Routes>
    </div>
  );
}
