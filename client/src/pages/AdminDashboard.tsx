import { useState, useEffect, useMemo, useRef } from 'react';
import { Routes, Route, NavLink, useLocation, Navigate, useNavigate } from 'react-router-dom';
import axios from 'axios';
import api from '../api/client';
import { formatDateTime, formatDate } from '../utils/formatDate';
import { useAuthStore } from '../store/authStore';
import { PhotoLightbox } from '../components/PhotoLightbox';
import { PolicyPage } from './admin/PolicyPage';
import { LateSuccessPaymentsPage } from './admin/LateSuccessPaymentsPage';
import { AdminSalePage } from './admin/AdminSalePage';
import { AdminIdentityPage } from './admin/AdminIdentityPage';
import { AdminReconciliationPage } from './admin/AdminReconciliationPage';
import { AdminVehiclesNeedsRediagnosticPage } from './admin/AdminVehiclesNeedsRediagnosticPage';
import { RejectReportModal } from '../components/admin/diagnostic/RejectReportModal';
import { SellerViolationResolutionPanel, type ViolationResolution } from '../components/admin/diagnostic/SellerViolationResolutionPanel';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

// ─── Types ────────────────────────────────────────────────────────────────────

interface AdminStats {
  totalUsers: number;
  totalVehicles: number;
  activeAuctions: number;
  finishedAuctions: number;
  pendingReports: number;
  approvedReports: number;
}

interface Auction {
  id: number; vehicleInfo: string; status: string; vin?: string;
  currentPrice: number; startTime: string; endTime: string; confirmedStart: boolean;
  winnerId?: number; winningBid?: number;
}

interface DiagItem  { id: number; title: string; fieldType: string; }
interface DiagGroup { id: number; name: string; items: DiagItem[]; }
interface DiagAnswer { inspectionItemId: number; rating: number | null; comment: string; photoUrls: string[]; skipped?: boolean; }

interface Report {
  id: number; brand: string; model: string; year: number; vin: string;
  status: string; createdAt: string; userName?: string; diagnosticName?: string;
  rejectedReason?: string;
  rejectionCategory?: string | null;
  commitmentStatus?: string | null;
  violationResolution?: ViolationResolution | null;
  template?: { name: string; groups: DiagGroup[] };
  existingAnswers?: DiagAnswer[];
}

interface UserItem {
  id: number; username: string; email: string; active: boolean;
  trusted: boolean;
  roles: string[]; balance?: number; availableBalance?: number;
  createdAt?: string;
}

interface DepositItem {
  id: number; userId: number; balance: number;
  blockedAmount: number; availableBalance: number;
  status: string; createdAt: string;
  username?: string; email?: string;
}

// ─── Status maps ──────────────────────────────────────────────────────────────

const REPORT_BACKEND_TO_UI: Record<string, string> = {
  PENDING:     'DRAFT',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED:   'SUBMITTED',
  APPROVED:    'APPROVED',
  REJECTED:    'REJECTED',
};

const REPORT_STATUSES_UI = ['ALL', 'DRAFT', 'IN_PROGRESS', 'SUBMITTED', 'APPROVED', 'REJECTED'];
const AUCTION_STATUSES = ['ALL', 'CREATED', 'ACTIVE', 'COMPLETED', 'CANCELLED'];

const BADGE_COLORS: Record<string, string> = {
  ACTIVE:      'bg-green-100 text-green-700',
  CREATED:     'bg-blue-100 text-blue-700',
  COMPLETED:   'bg-purple-100 text-purple-700',
  FINISHED:    'bg-purple-100 text-purple-700',
  CANCELLED:   'bg-red-100 text-red-700',
  EXPIRED:     'bg-orange-100 text-orange-700',
  SOLD:        'bg-teal-100 text-teal-700',
  DRAFT:       'bg-gray-100 text-gray-600',
  IN_PROGRESS: 'bg-blue-100 text-blue-700',
  SUBMITTED:   'bg-yellow-100 text-yellow-700',
  APPROVED:    'bg-green-100 text-green-700',
  REJECTED:    'bg-red-100 text-red-700',
};

const BADGE_LABELS: Record<string, string> = {
  ACTIVE: 'Активен', CREATED: 'Создан', COMPLETED: 'Завершён', FINISHED: 'Завершён',
  CANCELLED: 'Отменён', EXPIRED: 'Истёк', SOLD: 'Продан',
  DRAFT: 'Черновик', IN_PROGRESS: 'В работе',
  SUBMITTED: 'На проверке', APPROVED: 'Одобрен', REJECTED: 'Отклонён',
};

// ─── Shared UI ────────────────────────────────────────────────────────────────

const Badge = ({ status }: { status: string }) => (
  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${BADGE_COLORS[status] || 'bg-gray-100 text-gray-600'}`}>
    {BADGE_LABELS[status] || status}
  </span>
);

function SearchInput({ value, onChange, placeholder }: {
  value: string; onChange: (v: string) => void; placeholder: string;
}) {
  return (
    <div className="relative">
      <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
      </svg>
      <input
        type="text" value={value} onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        className="pl-9 pr-8 py-2 border border-gray-200 rounded-lg text-sm w-full focus:ring-2 focus:ring-blue-500 focus:outline-none bg-white"
      />
      {value && (
        <button onClick={() => onChange('')}
          className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 text-xs">✕</button>
      )}
    </div>
  );
}

function Alert({ msg, err }: { msg: string; err?: boolean }) {
  if (!msg) return null;
  return (
    <div className={`rounded-lg p-3 text-sm border ${err ? 'bg-red-50 border-red-200 text-red-700' : 'bg-green-50 border-green-200 text-green-700'}`}>
      {msg}
    </div>
  );
}

function Modal({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg max-h-[85vh] overflow-y-auto">
        {children}
      </div>
    </div>
  );
}

function ConfirmModal({ message, onYes, onNo }: { message: string; onYes: () => void; onNo: () => void }) {
  return (
    <Modal onClose={onNo}>
      <div className="p-6 space-y-4">
        <p className="text-gray-800">{message}</p>
        <div className="flex gap-2 justify-end">
          <button onClick={onNo} className="px-4 py-2 border border-gray-200 rounded-lg text-sm hover:bg-gray-50">Отмена</button>
          <button onClick={onYes} className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700">Подтвердить</button>
        </div>
      </div>
    </Modal>
  );
}

// ─── Sidebar ──────────────────────────────────────────────────────────────────

const NAV_ITEMS = [
  { path: '/admin', label: 'Dashboard', exact: true, icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
    </svg>
  )},
  { path: '/admin/users', label: 'Пользователи', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
    </svg>
  )},
  { path: '/admin/auctions', label: 'Аукционы', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
    </svg>
  )},
  { path: '/admin/deposits', label: 'Депозиты', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  )},
  { path: '/admin/settings', label: 'Настройки', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  )},
  { path: '/admin/identity', label: 'Личность', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  )},
  { path: '/admin/reconciliation', label: 'Сверка платежей', icon: (
    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
    </svg>
  )},
];

function AdminSidebar() {
  const location  = useLocation();
  const navigate  = useNavigate();
  const logoutFn  = useAuthStore(s => s.logout);
  const [diagOpen, setDiagOpen] = useState(
    location.pathname.startsWith('/admin/diagnostic')
  );

  const isDiagActive = location.pathname.startsWith('/admin/diagnostic');

  const handleLogout = async () => {
    await logoutFn();
    navigate('/login');
  };

  return (
    <aside className="w-60 bg-white border-r border-gray-100 flex flex-col shrink-0 h-screen sticky top-0">
      <div className="px-5 py-5 border-b border-gray-100">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center">
            <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
          </div>
          <div>
            <p className="text-sm font-bold text-gray-900">AutoAuction</p>
            <p className="text-xs text-gray-400">Администратор</p>
          </div>
        </div>
      </div>

      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        {/* Dashboard */}
        <NavLink to="/admin" end
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[0].icon}
          Dashboard
        </NavLink>

        {/* Пользователи */}
        <NavLink to="/admin/users"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[1].icon}
          Пользователи
        </NavLink>

        {/* Диагностика (expandable) */}
        <div>
          <button
            onClick={() => setDiagOpen(v => !v)}
            className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isDiagActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
            </svg>
            <span className="flex-1 text-left">Диагностика</span>
            <svg className={`w-4 h-4 transition-transform ${diagOpen ? 'rotate-90' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
          {diagOpen && (
            <div className="ml-8 mt-1 space-y-1">
              <NavLink to="/admin/diagnostic/templates"
                className={({ isActive }) =>
                  `block px-3 py-2 rounded-lg text-sm transition-colors ${
                    isActive ? 'bg-blue-50 text-blue-700 font-medium' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
                  }`}>
                Шаблоны
              </NavLink>
              <NavLink to="/admin/diagnostic/reports"
                className={({ isActive }) =>
                  `block px-3 py-2 rounded-lg text-sm transition-colors ${
                    isActive ? 'bg-blue-50 text-blue-700 font-medium' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
                  }`}>
                Отчёты
              </NavLink>
            </div>
          )}
        </div>

        {/* Аукционы */}
        <NavLink to="/admin/auctions"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[2].icon}
          Аукционы
        </NavLink>

        {/* Ценовая политика */}
        <NavLink to="/admin/policy"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[4].icon}
          Политика
        </NavLink>

        {/* Депозиты */}
        <NavLink to="/admin/deposits"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[3].icon}
          Депозиты
        </NavLink>

        {/* Поздние успешные платежи */}
        <NavLink to="/admin/payments/late-successes"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[3].icon}
          Поздние платежи
        </NavLink>

        {/* Настройки */}
        <NavLink to="/admin/settings"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[4].icon}
          Настройки
        </NavLink>

        {/* Подтверждение личности */}
        <NavLink to="/admin/identity"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[5].icon}
          Личность
        </NavLink>

        {/* Сверка платежей */}
        <NavLink to="/admin/reconciliation"
          className={({ isActive }) =>
            `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
              isActive ? 'bg-blue-50 text-blue-700' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
            }`}>
          {NAV_ITEMS[6].icon}
          Сверка платежей
        </NavLink>
      </nav>

      {/* Logout */}
      <div className="border-t border-gray-100 p-3 shrink-0">
        <button
          onClick={handleLogout}
          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-red-600 hover:bg-red-50 transition-colors">
          <svg className="w-5 h-5 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
          </svg>
          Выйти
        </button>
      </div>
    </aside>
  );
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

function DashboardPage() {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get('/admin/dashboard')
      .then(r => setStats(r.data))
      .finally(() => setLoading(false));
  }, []);

  const cards = stats ? [
    { label: 'Всего пользователей',    value: stats.totalUsers,      color: 'blue',   icon: '👤' },
    { label: 'Всего автомобилей',      value: stats.totalVehicles,   color: 'indigo', icon: '🚗' },
    { label: 'Активных аукционов',     value: stats.activeAuctions,  color: 'green',  icon: '🔥' },
    { label: 'Завершённых аукционов',  value: stats.finishedAuctions,color: 'purple', icon: '✅' },
    { label: 'Отчётов на проверке',    value: stats.pendingReports,  color: 'orange', icon: '⏳' },
    { label: 'Одобренных отчётов',     value: stats.approvedReports, color: 'teal',   icon: '✔️' },
  ] : [];

  const colorClasses: Record<string, string> = {
    blue:   'bg-blue-50 border-blue-100',
    indigo: 'bg-indigo-50 border-indigo-100',
    green:  'bg-green-50 border-green-100',
    purple: 'bg-purple-50 border-purple-100',
    orange: 'bg-orange-50 border-orange-100',
    teal:   'bg-teal-50 border-teal-100',
  };
  const numClasses: Record<string, string> = {
    blue: 'text-blue-700', indigo: 'text-indigo-700', green: 'text-green-700',
    purple: 'text-purple-700', orange: 'text-orange-700', teal: 'text-teal-700',
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Dashboard</h2>
        <p className="text-sm text-gray-500 mt-0.5">Общая статистика системы</p>
      </div>

      {loading ? (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {[...Array(6)].map((_, i) => (
            <div key={i} className="border rounded-2xl p-5 bg-gray-50 animate-pulse h-24" />
          ))}
        </div>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {cards.map(({ label, value, color, icon }) => (
            <div key={label} className={`border rounded-2xl p-5 ${colorClasses[color]}`}>
              <div className="flex items-start justify-between">
                <p className="text-sm text-gray-600 leading-tight">{label}</p>
                <span className="text-xl">{icon}</span>
              </div>
              <p className={`text-3xl font-bold mt-2 ${numClasses[color]}`}>{value ?? '—'}</p>
            </div>
          ))}
        </div>
      )}

      <div>
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Последние действия</h3>
        <RecentActivity />
      </div>
    </div>
  );
}

function RecentActivity() {
  const [auctions, setAuctions] = useState<Auction[]>([]);
  const [reports, setReports]   = useState<Report[]>([]);
  const [loading, setLoading]   = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/admin/auctions?size=5&sort=id,desc').catch(() => ({ data: { content: [] } })),
      api.get('/admin/diagnostic/reports?size=5&sort=createdAt,desc').catch(() => ({ data: { content: [] } })),
    ]).then(([aRes, rRes]) => {
      setAuctions(aRes.data.content || []);
      setReports(rRes.data.content || []);
    }).finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="h-32 bg-gray-50 rounded-2xl animate-pulse" />;

  const events = [
    ...auctions.map(a => ({
      type: 'auction' as const,
      id: a.id,
      label: `Аукцион #${a.id}: ${a.vehicleInfo || '—'}`,
      status: a.status,
      time: a.startTime,
    })),
    ...reports.map(r => ({
      type: 'report' as const,
      id: r.id,
      label: `Отчёт #${r.id}: ${r.brand} ${r.model} ${r.year}`,
      status: REPORT_BACKEND_TO_UI[r.status] || r.status,
      time: r.createdAt,
    })),
  ].sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime()).slice(0, 8);

  if (events.length === 0) {
    return <div className="text-center text-gray-400 py-8 text-sm">Нет данных</div>;
  }

  return (
    <div className="bg-white border border-gray-100 rounded-2xl divide-y divide-gray-50">
      {events.map((e, i) => (
        <div key={`${e.type}-${e.id}-${i}`} className="flex items-center gap-3 px-4 py-3">
          <div className={`w-2 h-2 rounded-full shrink-0 ${
            e.type === 'auction' ? 'bg-blue-400' : 'bg-orange-400'
          }`} />
          <p className="text-sm text-gray-700 flex-1 truncate">{e.label}</p>
          <Badge status={e.status} />
          <span className="text-xs text-gray-400 whitespace-nowrap shrink-0">{formatDate(e.time)}</span>
        </div>
      ))}
    </div>
  );
}

// ─── Users ────────────────────────────────────────────────────────────────────

function UsersPage() {
  const [users, setUsers]           = useState<UserItem[]>([]);
  const [loading, setLoading]       = useState(true);
  const [msg, setMsg]               = useState('');
  const [msgErr, setMsgErr]         = useState(false);
  const [filterRole, setFilterRole] = useState('');
  const [search, setSearch]         = useState('');
  const [roleTarget, setRoleTarget] = useState<UserItem | null>(null);
  const [newRole, setNewRole]       = useState('');
  const [viewTarget, setViewTarget] = useState<UserItem | null>(null);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    const p = filterRole ? `?role=${filterRole}` : '';
    api.get(`/admin/users${p}`)
      .then(r => setUsers(r.data))
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, [filterRole]);

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
      notify(`${u.username} ${u.active ? 'заблокирован' : 'разблокирован'}`);
      load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  // Slice 1A: operational controlled-beta trust toggle - NOT identity/KYC verification.
  const toggleTrust = async (u: UserItem) => {
    try {
      await api.patch(`/admin/users/${u.id}/${u.trusted ? 'untrust' : 'trust'}`);
      notify(`${u.username} ${u.trusted ? 'доверие отозвано' : 'доверен для участия в торгах'}`);
      load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const applyRole = async () => {
    if (!roleTarget || !newRole.trim()) return;
    try {
      await api.patch(`/admin/users/${roleTarget.id}/role`, { role: newRole.trim() });
      notify(`Роль ${roleTarget.username} изменена → ${newRole.replace('ROLE_', '')}`);
      setRoleTarget(null); load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const ROLE_FILTERS = [
    { value: '', label: 'Все' },
    { value: 'ROLE_USER', label: 'Пользователи' },
    { value: 'ROLE_DIAGNOSTIC', label: 'Диагносты' },
    { value: 'ROLE_ADMIN', label: 'Администраторы' },
  ];

  return (
    <div className="space-y-4">
      {roleTarget && (
        <Modal onClose={() => setRoleTarget(null)}>
          <div className="p-6 space-y-4">
            <h3 className="font-semibold">Сменить роль: <span className="text-blue-600">{roleTarget.username}</span></h3>
            <select value={newRole} onChange={e => setNewRole(e.target.value)}
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none">
              <option value="">— Выберите роль —</option>
              <option value="ROLE_USER">Пользователь</option>
              <option value="ROLE_DIAGNOSTIC">Диагност</option>
              <option value="ROLE_ADMIN">Администратор</option>
            </select>
            <div className="flex gap-2 justify-end">
              <button onClick={() => setRoleTarget(null)} className="px-4 py-2 border border-gray-200 rounded-lg text-sm hover:bg-gray-50">Отмена</button>
              <button onClick={applyRole} disabled={!newRole}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm disabled:opacity-50 hover:bg-blue-700">Применить</button>
            </div>
          </div>
        </Modal>
      )}

      {viewTarget && (
        <Modal onClose={() => setViewTarget(null)}>
          <div className="p-6 space-y-4">
            <div className="flex justify-between items-start">
              <h3 className="font-semibold text-gray-900">Пользователь #{viewTarget.id}</h3>
              <button onClick={() => setViewTarget(null)} className="text-gray-400 hover:text-gray-600">✕</button>
            </div>
            <div className="space-y-2 text-sm">
              <div className="flex gap-2">
                <span className="text-gray-500 w-32 shrink-0">Имя:</span>
                <span className="font-medium">{viewTarget.username}</span>
              </div>
              <div className="flex gap-2">
                <span className="text-gray-500 w-32 shrink-0">Email:</span>
                <span>{viewTarget.email}</span>
              </div>
              <div className="flex gap-2">
                <span className="text-gray-500 w-32 shrink-0">Роли:</span>
                <div className="flex gap-1 flex-wrap">
                  {viewTarget.roles.map(r => (
                    <span key={r} className="px-2 py-0.5 bg-blue-50 text-blue-700 rounded-full text-xs">{r.replace('ROLE_', '')}</span>
                  ))}
                </div>
              </div>
              <div className="flex gap-2">
                <span className="text-gray-500 w-32 shrink-0">Статус:</span>
                <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${viewTarget.active ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                  {viewTarget.active ? 'Активен' : 'Заблокирован'}
                </span>
              </div>
              <div className="flex gap-2">
                <span className="text-gray-500 w-32 shrink-0">Доверие:</span>
                <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${viewTarget.trusted ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                  {viewTarget.trusted ? 'Доверен' : 'Не рассмотрен'}
                </span>
              </div>
              {viewTarget.createdAt && (
                <div className="flex gap-2">
                  <span className="text-gray-500 w-32 shrink-0">Регистрация:</span>
                  <span>{formatDate(viewTarget.createdAt)}</span>
                </div>
              )}
            </div>
            <div className="flex gap-2 pt-2">
              <button onClick={() => { toggleBlock(viewTarget); setViewTarget(null); }}
                className={`px-3 py-2 rounded-lg text-sm ${viewTarget.active ? 'bg-red-50 text-red-700 hover:bg-red-100' : 'bg-green-50 text-green-700 hover:bg-green-100'}`}>
                {viewTarget.active ? 'Заблокировать' : 'Разблокировать'}
              </button>
              <button onClick={() => { toggleTrust(viewTarget); setViewTarget(null); }}
                className={`px-3 py-2 rounded-lg text-sm ${viewTarget.trusted ? 'bg-gray-50 text-gray-600 hover:bg-gray-100' : 'bg-green-50 text-green-700 hover:bg-green-100'}`}>
                {viewTarget.trusted ? 'Отозвать доверие' : 'Доверить'}
              </button>
              <button onClick={() => { setViewTarget(null); setRoleTarget(viewTarget); setNewRole(viewTarget.roles[0] || ''); }}
                className="px-3 py-2 rounded-lg text-sm bg-blue-50 text-blue-700 hover:bg-blue-100">
                Изменить роль
              </button>
            </div>
          </div>
        </Modal>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Пользователи</h2>
          <p className="text-sm text-gray-500 mt-0.5">Найдено: {filtered.length}</p>
        </div>
      </div>

      <Alert msg={msg} err={msgErr} />

      <div className="flex flex-col sm:flex-row gap-3">
        <div className="flex gap-2 flex-wrap">
          {ROLE_FILTERS.map(({ value, label }) => (
            <button key={value} onClick={() => setFilterRole(value)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                filterRole === value ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {label}
            </button>
          ))}
        </div>
        <div className="sm:ml-auto sm:w-72">
          <SearchInput value={search} onChange={setSearch} placeholder="Имя, email..." />
        </div>
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl overflow-hidden shadow-sm">
        {loading ? (
          <div className="p-8 text-center text-gray-400">Загрузка...</div>
        ) : filtered.length === 0 ? (
          <div className="p-8 text-center text-gray-400">Пользователи не найдены</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-100">
                <tr>
                  {['Имя', 'Email', 'Роль', 'Дата регистрации', 'Статус', 'Доверие', 'Действия'].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {filtered.map(u => (
                  <tr key={u.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-medium text-gray-900">{u.username}</td>
                    <td className="px-4 py-3 text-gray-500">{u.email}</td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1 flex-wrap">
                        {u.roles.map(r => (
                          <span key={r} className="px-2 py-0.5 bg-blue-50 text-blue-700 rounded-full text-xs font-medium">
                            {r.replace('ROLE_', '')}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">
                      {u.createdAt ? formatDate(u.createdAt) : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${u.active ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                        {u.active ? 'Активен' : 'Заблокирован'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${u.trusted ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                        {u.trusted ? 'Доверен' : 'Не рассмотрен'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1.5">
                        <button onClick={() => setViewTarget(u)}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 font-medium">
                          Просмотр
                        </button>
                        <button onClick={() => toggleBlock(u)}
                          className={`text-xs px-2.5 py-1.5 rounded-lg font-medium ${u.active ? 'bg-red-50 text-red-600 hover:bg-red-100' : 'bg-green-50 text-green-600 hover:bg-green-100'}`}>
                          {u.active ? 'Заблок.' : 'Разблок.'}
                        </button>
                        <button onClick={() => toggleTrust(u)}
                          className={`text-xs px-2.5 py-1.5 rounded-lg font-medium ${u.trusted ? 'bg-gray-50 text-gray-600 hover:bg-gray-100' : 'bg-green-50 text-green-600 hover:bg-green-100'}`}>
                          {u.trusted ? 'Отозвать' : 'Доверить'}
                        </button>
                        <button onClick={() => { setRoleTarget(u); setNewRole(u.roles[0] || ''); }}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 font-medium">
                          Роль
                        </button>
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

// ─── Reports ──────────────────────────────────────────────────────────────────

function AdminDiagAccordion({ template, answers }: {
  template: { name: string; groups: DiagGroup[] };
  answers: DiagAnswer[];
}) {
  const [openGroups, setOpenGroups] = useState<Set<number>>(
    () => new Set((template.groups || []).map(g => g.id))
  );
  const [lightbox, setLightbox] = useState<{ urls: string[]; index: number } | null>(null);
  const answerMap = Object.fromEntries((answers || []).map(a => [a.inspectionItemId, a]));

  const toggleGroup = (id: number) => setOpenGroups(prev => {
    const s = new Set(prev); s.has(id) ? s.delete(id) : s.add(id); return s;
  });

  const isFilled = (item: DiagItem, ans?: DiagAnswer): boolean => {
    if (!ans) return false;
    if (item.fieldType === 'NUMBER') return ans.rating != null && ans.rating > 0;
    if (item.fieldType === 'PHOTO') return (ans.photoUrls?.length ?? 0) > 0;
    if (item.fieldType === 'CHECKBOX') return ans.comment === 'true' || ans.comment === 'false';
    return (ans.comment?.trim().length ?? 0) > 0;
  };

  const renderVal = (item: DiagItem, ans?: DiagAnswer) => {
    if (ans?.skipped) return <span className="text-sm text-gray-400 italic">Пропущен диагностом</span>;
    if (!ans || !isFilled(item, ans)) {
      return <span className="text-sm text-gray-300 italic">Не заполнено</span>;
    }
    switch (item.fieldType) {
      case 'NUMBER': {
        const labels = ['', 'Плохо', 'Удовл.', 'Нормально', 'Хорошо', 'Отлично'];
        return (
          <div className="flex items-center gap-2">
            <div className="flex gap-0.5">
              {[1,2,3,4,5].map(i => (
                <div key={i} className={`w-6 h-6 rounded text-xs flex items-center justify-center font-semibold ${
                  (ans.rating ?? 0) >= i ? 'bg-yellow-400 text-white' : 'bg-gray-100 text-gray-300'
                }`}>{i}</div>
              ))}
            </div>
            {ans.rating && <span className="text-xs text-gray-500">{labels[ans.rating]}</span>}
          </div>
        );
      }
      case 'PHOTO':
        return (
          <div className="flex flex-wrap gap-1.5 mt-1">
            {(ans.photoUrls || []).map((url, i) => (
              <button key={i} type="button"
                onClick={() => setLightbox({ urls: ans.photoUrls || [], index: i })}>
                <img src={url} alt="" className="w-20 h-20 object-cover rounded-lg border hover:opacity-80 transition-opacity cursor-pointer" />
              </button>
            ))}
          </div>
        );
      case 'CHECKBOX':
        return (
          <span className={`text-sm font-medium ${ans.comment === 'true' ? 'text-green-600' : 'text-red-500'}`}>
            {ans.comment === 'true' ? '✓ Да' : '✕ Нет'}
          </span>
        );
      default:
        return <span className="text-sm text-gray-800 whitespace-pre-wrap">{ans.comment}</span>;
    }
  };

  const allItems = (template.groups || []).flatMap(g => g.items);
  const filledCount = allItems.filter(i => isFilled(i, answerMap[i.id])).length;

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between px-1 mb-1">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{template.name}</p>
        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
          filledCount === allItems.length ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
        }`}>{filledCount}/{allItems.length} заполнено</span>
      </div>
      {(template.groups || []).map(group => {
        const groupFilled = group.items.filter(i => isFilled(i, answerMap[i.id])).length;
        return (
          <div key={group.id} className="border border-gray-100 rounded-xl overflow-hidden">
            <button onClick={() => toggleGroup(group.id)}
              className="w-full flex justify-between items-center px-4 py-3 hover:bg-gray-50 text-left bg-white">
              <div className="flex items-center gap-2.5">
                <span className="font-medium text-gray-900 text-sm">{group.name}</span>
                <span className={`text-xs px-1.5 py-0.5 rounded-full ${
                  groupFilled === group.items.length ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
                }`}>{groupFilled}/{group.items.length}</span>
              </div>
              <svg className={`w-4 h-4 text-gray-400 transition-transform ${openGroups.has(group.id) ? 'rotate-180' : ''}`}
                fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {openGroups.has(group.id) && (
              <div className="divide-y divide-gray-50">
                {group.items.map(item => {
                  const ans = answerMap[item.id];
                  const filled = isFilled(item, ans);
                  return (
                    <div key={item.id} className="px-4 py-3">
                      <div className="flex items-start gap-2 mb-1.5">
                        <div className={`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${
                          filled ? 'bg-green-400' : item.isRequired ? 'bg-red-300' : 'bg-gray-200'
                        }`} />
                        <p className="text-xs font-medium text-gray-600">
                          {item.title}
                          {item.isRequired && <span className="text-red-400 ml-0.5">*</span>}
                        </p>
                      </div>
                      <div className="ml-3.5">{renderVal(item, ans)}</div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}

      {lightbox && (
        <PhotoLightbox
          urls={lightbox.urls}
          index={lightbox.index}
          onClose={() => setLightbox(null)}
          onPrev={() => setLightbox(lb => lb && { ...lb, index: (lb.index - 1 + lb.urls.length) % lb.urls.length })}
          onNext={() => setLightbox(lb => lb && { ...lb, index: (lb.index + 1) % lb.urls.length })}
        />
      )}
    </div>
  );
}

function ReportsPage() {
  const [reports, setReports]   = useState<Report[]>([]);
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [msgErr, setMsgErr]     = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch]     = useState('');
  const [rejectTarget, setRejectTarget] = useState<number | null>(null);
  const [detailReport, setDetailReport] = useState<Report | null>(null);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    setLoading(true);
    api.get('/admin/diagnostic/reports?size=200&sort=createdAt,desc')
      .then(r => setReports(r.data.content || []))
      .catch(() => notify('Ошибка загрузки отчётов', true))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const uiStatus = (s: string) => REPORT_BACKEND_TO_UI[s] || s;

  const filtered = useMemo(() => {
    let list = statusFilter === 'ALL'
      ? reports
      : reports.filter(r => uiStatus(r.status) === statusFilter);
    const q = search.trim().toLowerCase();
    if (q) list = list.filter(r =>
      r.vin?.toLowerCase().includes(q) ||
      `${r.brand} ${r.model}`.toLowerCase().includes(q) ||
      r.diagnosticName?.toLowerCase().includes(q) ||
      r.userName?.toLowerCase().includes(q)
    );
    return list;
  }, [reports, statusFilter, search]);

  const approve = async (id: number) => {
    try { await api.post(`/admin/diagnostic/reports/${id}/approve`); notify(`Отчёт #${id} одобрен`); load(); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const reject = async (reason: string, category: string) => {
    if (rejectTarget == null) return;
    try {
      await api.post(`/admin/diagnostic/reports/${rejectTarget}/reject`
        + `?reason=${encodeURIComponent(reason)}&category=${encodeURIComponent(category)}`);
      notify(`Отчёт #${rejectTarget} отклонён`);
      setRejectTarget(null); load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); setRejectTarget(null); }
  };

  // MVP-003: guards the detail panel against stale responses - an older loadDetail() (another
  // report's "Детали" click, or the SellerViolationResolutionPanel onDone refetch) resolving
  // after a newer one, or after the panel was closed. Every loadDetail() and closeDetail()
  // bumps the generation; only a response whose captured generation is still current may set
  // detailReport or notify (same pattern as AdminSalePage's requestGeneration).
  const detailGeneration = useRef(0);

  const loadDetail = async (id: number) => {
    const generation = ++detailGeneration.current;
    try {
      const r = await api.get(`/admin/diagnostic/reports/${id}`);
      if (generation === detailGeneration.current) setDetailReport(r.data);
    } catch (e: any) {
      if (generation === detailGeneration.current) notify('Ошибка загрузки детали', true);
    }
  };

  const closeDetail = () => {
    detailGeneration.current += 1;
    setDetailReport(null);
  };

  const isReviewable = (r: Report) => r.status === 'COMPLETED';

  return (
    <div className="space-y-4">
      {rejectTarget != null && (
        <RejectReportModal
          requestId={rejectTarget}
          onConfirm={reject} onCancel={() => setRejectTarget(null)}
        />
      )}

      {detailReport && (
        <div className="fixed inset-0 z-50 flex">
          {/* backdrop */}
          <div className="flex-1 bg-black/50" onClick={closeDetail} />
          {/* side panel */}
          <div className="w-full max-w-2xl bg-white shadow-2xl flex flex-col h-full overflow-hidden">
            {/* Header */}
            <div className="px-5 py-4 border-b bg-white flex items-start gap-3 shrink-0">
              <div className="flex-1 min-w-0">
                <h2 className="font-bold text-lg text-gray-900">
                  {detailReport.brand} {detailReport.model} {detailReport.year}
                </h2>
                <p className="text-xs text-gray-400 mt-0.5">
                  Отчёт #{detailReport.id} · VIN: {detailReport.vin}
                </p>
              </div>
              <button onClick={closeDetail}
                className="shrink-0 w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400">
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {/* Action bar — visible only for reviewable reports */}
            {isReviewable(detailReport) && (
              <div className="px-5 py-3 bg-amber-50 border-b border-amber-100 flex items-center gap-3 shrink-0">
                <svg className="w-4 h-4 text-amber-600 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                    d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
                <span className="text-sm text-amber-800 font-medium flex-1">Ожидает проверки администратора</span>
                <button onClick={() => { approve(detailReport.id); closeDetail(); }}
                  className="px-4 py-1.5 bg-green-600 text-white rounded-lg text-sm hover:bg-green-700 font-semibold">
                  Одобрить
                </button>
                <button onClick={() => { setRejectTarget(detailReport.id); closeDetail(); }}
                  className="px-4 py-1.5 bg-red-600 text-white rounded-lg text-sm hover:bg-red-700 font-semibold">
                  Отклонить
                </button>
              </div>
            )}

            {/* Scrollable body */}
            <div className="flex-1 overflow-y-auto p-5 space-y-4">
              {/* Meta info */}
              <div className="grid grid-cols-2 gap-3 text-sm">
                {[
                  { label: 'Заявитель',  value: detailReport.userName || '—' },
                  { label: 'Диагност',   value: detailReport.diagnosticName || '—' },
                  { label: 'Дата',       value: formatDate(detailReport.createdAt) },
                ].map(({ label, value }) => (
                  <div key={label} className="bg-gray-50 rounded-xl p-3">
                    <p className="text-xs text-gray-400 mb-0.5">{label}</p>
                    <p className="font-medium text-gray-900">{value}</p>
                  </div>
                ))}
                <div className="bg-gray-50 rounded-xl p-3">
                  <p className="text-xs text-gray-400 mb-0.5">Статус</p>
                  <Badge status={uiStatus(detailReport.status)} />
                </div>
              </div>

              {detailReport.rejectedReason && (
                <div className="p-4 bg-red-50 border border-red-100 rounded-xl">
                  <p className="text-red-700 font-semibold text-sm mb-1">Причина отклонения</p>
                  <p className="text-red-600 text-sm">{detailReport.rejectedReason}</p>
                </div>
              )}

              {detailReport.rejectionCategory === 'SELLER_VIOLATION' && (
                <SellerViolationResolutionPanel
                  requestId={detailReport.id}
                  commitmentStatus={detailReport.commitmentStatus}
                  violationResolution={detailReport.violationResolution}
                  onDone={() => loadDetail(detailReport.id)}
                />
              )}

              {detailReport.template && detailReport.existingAnswers ? (
                <AdminDiagAccordion
                  template={detailReport.template}
                  answers={detailReport.existingAnswers}
                />
              ) : (
                <div className="text-center py-12 text-gray-400 text-sm">
                  <svg className="w-10 h-10 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                  </svg>
                  Ответы на анкету отсутствуют
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Диагностические отчёты</h2>
          <p className="text-sm text-gray-500 mt-0.5">Найдено: {filtered.length}</p>
        </div>
      </div>

      <Alert msg={msg} err={msgErr} />

      <div className="flex flex-col sm:flex-row gap-3">
        <div className="flex gap-2 flex-wrap items-center">
          {REPORT_STATUSES_UI.map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {s === 'ALL' ? 'Все' : <Badge status={s} />}
            </button>
          ))}
        </div>
        <div className="sm:ml-auto sm:w-72">
          <SearchInput value={search} onChange={setSearch} placeholder="VIN, авто, диагност..." />
        </div>
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl overflow-hidden shadow-sm">
        {loading ? (
          <div className="p-8 text-center text-gray-400">Загрузка...</div>
        ) : filtered.length === 0 ? (
          <div className="p-8 text-center text-gray-400">Отчёты не найдены</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-100">
                <tr>
                  {['ID', 'Автомобиль', 'VIN', 'Заявитель', 'Диагност', 'Дата', 'Статус', 'Действия'].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {filtered.map(r => (
                  <tr key={r.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-gray-400 text-xs">#{r.id}</td>
                    <td className="px-4 py-3 font-medium">{r.brand} {r.model} {r.year}</td>
                    <td className="px-4 py-3 text-gray-500 font-mono text-xs">{r.vin}</td>
                    <td className="px-4 py-3 text-gray-600">{r.userName || '—'}</td>
                    <td className="px-4 py-3 text-gray-600">{r.diagnosticName || '—'}</td>
                    <td className="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">{formatDate(r.createdAt)}</td>
                    <td className="px-4 py-3"><Badge status={uiStatus(r.status)} /></td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1.5">
                        <button onClick={() => loadDetail(r.id)}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 font-medium">
                          Детали
                        </button>
                        {isReviewable(r) && (
                          <>
                            <button onClick={() => approve(r.id)}
                              className="text-xs px-2.5 py-1.5 rounded-lg bg-green-600 text-white hover:bg-green-700 font-medium">
                              Одобрить
                            </button>
                            <button onClick={() => setRejectTarget(r.id)}
                              className="text-xs px-2.5 py-1.5 rounded-lg bg-red-600 text-white hover:bg-red-700 font-medium">
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

// ─── Templates ────────────────────────────────────────────────────────────────

// ── Seed data ─────────────────────────────────────────────────────────────────
interface SeedItem { title: string; fieldType: string; isRequired: boolean; description?: string; }
interface SeedGroup { name: string; items: SeedItem[]; }
interface SeedTemplate { vehicleType: string; name: string; groups: SeedGroup[]; }

const _COMMON_GROUPS: SeedGroup[] = [
  {
    name: '🚗 Кузов и экстерьер',
    items: [
      { title: 'Общая оценка состояния кузова', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние ЛКП (лакокрасочное покрытие)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Наличие сколов и царапин', fieldType: 'SELECT', isRequired: true, description: 'Нет\nЕдиничные (1–3)\nМножественные (более 3)\nКритические повреждения' },
      { title: 'Ржавчина / коррозия', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Следы кузовного ремонта (шпаклёвка, рихтовка)', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Состояние дверей и петель', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние бамперов', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние крыши', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние лобового стекла', fieldType: 'NUMBER', isRequired: true },
      { title: 'Трещины / сколы на стёклах', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Замки и ручки дверей', fieldType: 'NUMBER', isRequired: true },
      { title: 'Дополнительные замечания по кузову', fieldType: 'TEXTAREA', isRequired: false },
      { title: 'Фото — передняя часть', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — задняя часть', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — левый бок', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — правый бок', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — крыша', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — повреждения / дефекты', fieldType: 'PHOTO', isRequired: false },
    ],
  },
  {
    name: '🔧 Двигательный отсек',
    items: [
      { title: 'Общая оценка состояния двигателя', fieldType: 'NUMBER', isRequired: true },
      { title: 'Посторонние звуки при работе', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Дымность выхлопа', fieldType: 'SELECT', isRequired: true, description: 'Нет дыма\nЛёгкий белый (прогрев)\nПостоянный белый (антифриз)\nСиний (масло)\nЧёрный (топливо)' },
      { title: 'Утечки масла / жидкостей', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Уровень моторного масла', fieldType: 'SELECT', isRequired: true, description: 'Норма\nНиже нормы\nВыше нормы' },
      { title: 'Уровень охлаждающей жидкости', fieldType: 'SELECT', isRequired: true, description: 'Норма\nНиже нормы\nВыше нормы' },
      { title: 'Уровень тормозной жидкости', fieldType: 'SELECT', isRequired: true, description: 'Норма\nНиже нормы\nВыше нормы' },
      { title: 'Состояние ремня/цепи ГРМ', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние воздушного фильтра', fieldType: 'NUMBER', isRequired: false },
      { title: 'Фото — двигатель (вид спереди)', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — двигатель (общий план)', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — уровень масла на щупе', fieldType: 'PHOTO', isRequired: false },
      { title: 'Комментарии по двигателю', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '⚙️ Трансмиссия',
    items: [
      { title: 'Тип КПП', fieldType: 'SELECT', isRequired: true, description: 'Механическая (МКПП)\nАвтоматическая (АКПП)\nВариатор (CVT)\nРоботизированная (DSG/AMT/DCT)' },
      { title: 'Состояние КПП', fieldType: 'NUMBER', isRequired: true },
      { title: 'Чёткость переключения передач', fieldType: 'NUMBER', isRequired: true },
      { title: 'Рывки / пробуксовки при разгоне', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Посторонние звуки при переключении', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Уровень масла в КПП', fieldType: 'SELECT', isRequired: false, description: 'Норма\nНиже нормы\nВыше нормы\nНе проверялся' },
      { title: 'Комментарии по трансмиссии', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '🛞 Ходовая часть и тормоза',
    items: [
      { title: 'Остаток протектора передних шин (%)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Остаток протектора задних шин (%)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние дисков (деформация, трещины)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Шины одного производителя / размера', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Состояние передней подвески', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние задней подвески', fieldType: 'NUMBER', isRequired: true },
      { title: 'Амортизаторы (течи, посторонние шумы)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние рулевого управления', fieldType: 'NUMBER', isRequired: true },
      { title: 'Тормозные колодки передние (мм)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Тормозные колодки задние (мм)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Тормозные диски передние (мм)', fieldType: 'NUMBER', isRequired: false },
      { title: 'Тормозные диски задние (мм)', fieldType: 'NUMBER', isRequired: false },
      { title: 'Стояночный тормоз', fieldType: 'NUMBER', isRequired: true },
      { title: 'Фото — переднее левое колесо', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — переднее правое колесо', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — заднее левое колесо', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — заднее правое колесо', fieldType: 'PHOTO', isRequired: true },
      { title: 'Комментарии по ходовой', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '💡 Электрооборудование',
    items: [
      { title: 'Состояние аккумулятора', fieldType: 'NUMBER', isRequired: true },
      { title: 'Напряжение АКБ (В)', fieldType: 'NUMBER', isRequired: false },
      { title: 'Генератор / зарядная система', fieldType: 'NUMBER', isRequired: true },
      { title: 'Работа всех внешних огней', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Работа приборной панели', fieldType: 'NUMBER', isRequired: true },
      { title: 'Ошибки OBD / Check Engine', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Работа кондиционера / климат-контроля', fieldType: 'NUMBER', isRequired: true },
      { title: 'Работа электростёкол', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Работа центрального замка', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Работа электрозеркал', fieldType: 'CHECKBOX', isRequired: false },
      { title: 'Подогрев сидений', fieldType: 'CHECKBOX', isRequired: false },
      { title: 'Подогрев зеркал / заднего стекла', fieldType: 'CHECKBOX', isRequired: false },
      { title: 'Комментарии по электрике', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '🪑 Салон и интерьер',
    items: [
      { title: 'Общее состояние салона', fieldType: 'NUMBER', isRequired: true },
      { title: 'Тип обивки сидений', fieldType: 'SELECT', isRequired: false, description: 'Ткань\nКожа натуральная\nИскусственная кожа\nКомбинированная' },
      { title: 'Состояние сидений', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние потолка / обшивки дверей', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние коврового покрытия', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние рулевого колеса', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние приборной панели (торпедо)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Работа мультимедиа / навигации', fieldType: 'NUMBER', isRequired: false },
      { title: 'Посторонние запахи', fieldType: 'SELECT', isRequired: true, description: 'Нет\nНезначительный\nВыраженный (табак/влага)\nСильный (горелое/хим.)' },
      { title: 'Фото — передняя часть салона', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — задняя часть салона', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — приборная панель', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — рулевое колесо', fieldType: 'PHOTO', isRequired: false },
      { title: 'Фото — состояние сидений', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — багажное отделение', fieldType: 'PHOTO', isRequired: true },
      { title: 'Замечания по салону', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '📄 Документы и юридическая чистота',
    items: [
      { title: 'Соответствие VIN документам', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'VIN на кузове совпадает с ПТС', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Наличие технического паспорта (ПТС)', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Пробег соответствует видимому износу', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Участие в ДТП по базе данных', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Залоговый / арестованный статус', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Количество владельцев', fieldType: 'NUMBER', isRequired: true },
      { title: 'Регулярность технического обслуживания', fieldType: 'SELECT', isRequired: false, description: 'Регулярное (по регламенту)\nНерегулярное\nНеизвестно' },
      { title: 'История обслуживания / примечания', fieldType: 'TEXTAREA', isRequired: false },
      { title: 'Фото — технический паспорт', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — VIN-номер на кузове', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — спидометр (пробег)', fieldType: 'PHOTO', isRequired: true },
    ],
  },
];

const _MOTO_GROUPS: SeedGroup[] = [
  {
    name: '🏍️ Рама, кузов и экстерьер',
    items: [
      { title: 'Общее состояние мотоцикла', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние рамы (трещины, сварка)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Следы падений / ударов', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Состояние пластика / обтекателей', fieldType: 'NUMBER', isRequired: false },
      { title: 'Состояние лакокрасочного покрытия', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние сиденья', fieldType: 'NUMBER', isRequired: true },
      { title: 'Ржавчина / коррозия', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Фото — левый бок', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — правый бок', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — передняя часть', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — задняя часть', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — рама (детально)', fieldType: 'PHOTO', isRequired: false },
    ],
  },
  {
    name: '🔧 Двигатель',
    items: [
      { title: 'Состояние двигателя', fieldType: 'NUMBER', isRequired: true },
      { title: 'Посторонние звуки при работе', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Утечки масла / жидкостей', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Дымность выхлопа', fieldType: 'SELECT', isRequired: true, description: 'Нет\nЛёгкий белый\nСиний (масло)\nЧёрный (топливо)' },
      { title: 'Уровень масла', fieldType: 'SELECT', isRequired: true, description: 'Норма\nНиже нормы\nВыше нормы' },
      { title: 'Состояние воздушного фильтра', fieldType: 'NUMBER', isRequired: false },
      { title: 'Состояние выхлопной системы', fieldType: 'NUMBER', isRequired: true },
      { title: 'Фото — двигатель', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — выхлопная труба', fieldType: 'PHOTO', isRequired: false },
      { title: 'Фото — уровень масла', fieldType: 'PHOTO', isRequired: false },
      { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '⚙️ Трансмиссия и привод',
    items: [
      { title: 'Тип привода', fieldType: 'SELECT', isRequired: true, description: 'Цепь\nРемень\nКардан' },
      { title: 'Состояние цепи / ремня / кардана', fieldType: 'NUMBER', isRequired: true },
      { title: 'Натяжение цепи', fieldType: 'SELECT', isRequired: false, description: 'Норма\nОслаблена\nПеретянута' },
      { title: 'Состояние звёздочек', fieldType: 'NUMBER', isRequired: false },
      { title: 'Состояние сцепления', fieldType: 'NUMBER', isRequired: true },
      { title: 'Тип КПП', fieldType: 'SELECT', isRequired: false, description: 'Механическая\nАвтоматическая\nВариатор' },
      { title: 'Фото — цепь / привод', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — звёздочки', fieldType: 'PHOTO', isRequired: false },
      { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '🛞 Ходовая часть и тормоза',
    items: [
      { title: 'Состояние передней вилки', fieldType: 'NUMBER', isRequired: true },
      { title: 'Течи передней вилки', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Состояние заднего амортизатора', fieldType: 'NUMBER', isRequired: true },
      { title: 'Остаток протектора переднего колеса (%)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Остаток протектора заднего колеса (%)', fieldType: 'NUMBER', isRequired: true },
      { title: 'Состояние дисков / обода', fieldType: 'NUMBER', isRequired: true },
      { title: 'Передние тормоза', fieldType: 'NUMBER', isRequired: true },
      { title: 'Задние тормоза', fieldType: 'NUMBER', isRequired: true },
      { title: 'Наличие ABS', fieldType: 'CHECKBOX', isRequired: false },
      { title: 'Фото — переднее колесо + вилка', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — заднее колесо', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — тормозные диски', fieldType: 'PHOTO', isRequired: false },
      { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '💡 Электрооборудование',
    items: [
      { title: 'Состояние аккумулятора', fieldType: 'NUMBER', isRequired: true },
      { title: 'Работа всех огней', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Работа приборной панели', fieldType: 'NUMBER', isRequired: true },
      { title: 'Работа стартера', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Ошибки / предупреждения', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Фото — приборная панель', fieldType: 'PHOTO', isRequired: true },
      { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
    ],
  },
  {
    name: '📄 Документы и юридическая чистота',
    items: [
      { title: 'Соответствие VIN документам', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Наличие техпаспорта', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Участие в ДТП по базе данных', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Залоговый / арестованный статус', fieldType: 'CHECKBOX', isRequired: true },
      { title: 'Количество владельцев', fieldType: 'NUMBER', isRequired: true },
      { title: 'История обслуживания', fieldType: 'TEXTAREA', isRequired: false },
      { title: 'Фото — техпаспорт', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — VIN-номер', fieldType: 'PHOTO', isRequired: true },
      { title: 'Фото — спидометр (пробег)', fieldType: 'PHOTO', isRequired: true },
    ],
  },
];

const _SUV_EXTRA: SeedGroup = {
  name: '🏔️ Полный привод и внедорожные качества',
  items: [
    { title: 'Тип привода', fieldType: 'SELECT', isRequired: true, description: 'Передний (FWD)\nЗадний (RWD)\nПолный постоянный (AWD)\nПолный подключаемый (4WD)' },
    { title: 'Состояние раздаточной коробки', fieldType: 'NUMBER', isRequired: false },
    { title: 'Работа блокировок дифференциала', fieldType: 'CHECKBOX', isRequired: false },
    { title: 'Переключение режимов 4WD', fieldType: 'NUMBER', isRequired: false },
    { title: 'Состояние карданных валов', fieldType: 'NUMBER', isRequired: false },
    { title: 'Клиренс — дорожный просвет (мм)', fieldType: 'NUMBER', isRequired: false },
    { title: 'Состояние защиты картера', fieldType: 'NUMBER', isRequired: false },
    { title: 'Фото — защита днища', fieldType: 'PHOTO', isRequired: false },
    { title: 'Фото — подкапотное (общий план)', fieldType: 'PHOTO', isRequired: false },
    { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
  ],
};

const _TRUCK_EXTRA: SeedGroup = {
  name: '📦 Грузовой отсек и спецоборудование',
  items: [
    { title: 'Состояние грузового кузова / платформы', fieldType: 'NUMBER', isRequired: true },
    { title: 'Грузоподъёмность соответствует документам', fieldType: 'CHECKBOX', isRequired: true },
    { title: 'Состояние бортов / пола', fieldType: 'NUMBER', isRequired: true },
    { title: 'Наличие / состояние тента', fieldType: 'NUMBER', isRequired: false },
    { title: 'Наличие гидроборта / лебёдки', fieldType: 'CHECKBOX', isRequired: false },
    { title: 'Техническая документация (тахограф)', fieldType: 'CHECKBOX', isRequired: false },
    { title: 'Соответствие категории ТС', fieldType: 'CHECKBOX', isRequired: true },
    { title: 'Фото — грузовая платформа (вид сверху)', fieldType: 'PHOTO', isRequired: true },
    { title: 'Фото — грузовая платформа (вид сзади)', fieldType: 'PHOTO', isRequired: true },
    { title: 'Фото — кабина водителя', fieldType: 'PHOTO', isRequired: true },
    { title: 'Фото — борта / дно кузова', fieldType: 'PHOTO', isRequired: false },
    { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
  ],
};

const _VAN_EXTRA: SeedGroup = {
  name: '🚐 Пассажирский / грузовой отсек',
  items: [
    { title: 'Тип фургона', fieldType: 'SELECT', isRequired: true, description: 'Пассажирский\nГрузовой\nГрузопассажирский\nСпецавтомобиль' },
    { title: 'Состояние отсека (пол, стены, потолок)', fieldType: 'NUMBER', isRequired: true },
    { title: 'Количество мест (пассажирский)', fieldType: 'NUMBER', isRequired: false },
    { title: 'Состояние пассажирских сидений', fieldType: 'NUMBER', isRequired: false },
    { title: 'Наличие перегородки кабина/кузов', fieldType: 'CHECKBOX', isRequired: false },
    { title: 'Состояние сдвижной двери', fieldType: 'NUMBER', isRequired: true },
    { title: 'Состояние задних дверей', fieldType: 'NUMBER', isRequired: true },
    { title: 'Фото — внутри отсека', fieldType: 'PHOTO', isRequired: true },
    { title: 'Фото — задние двери', fieldType: 'PHOTO', isRequired: true },
    { title: 'Фото — сдвижная дверь', fieldType: 'PHOTO', isRequired: false },
    { title: 'Фото — состояние пола', fieldType: 'PHOTO', isRequired: false },
    { title: 'Комментарии', fieldType: 'TEXTAREA', isRequired: false },
  ],
};

const SEED_TEMPLATES: SeedTemplate[] = [
  { vehicleType: 'SEDAN',      name: 'Диагностика седана — полный осмотр',          groups: _COMMON_GROUPS },
  { vehicleType: 'SUV',        name: 'Диагностика внедорожника — полный осмотр',     groups: [..._COMMON_GROUPS, _SUV_EXTRA] },
  { vehicleType: 'TRUCK',      name: 'Диагностика грузового автомобиля',             groups: [..._COMMON_GROUPS, _TRUCK_EXTRA] },
  { vehicleType: 'VAN',        name: 'Диагностика фургона / микроавтобуса',          groups: [..._COMMON_GROUPS, _VAN_EXTRA] },
  { vehicleType: 'MOTORCYCLE', name: 'Диагностика мотоцикла — полный осмотр',        groups: _MOTO_GROUPS },
  { vehicleType: 'OTHER',      name: 'Универсальная диагностика ТС',                 groups: _COMMON_GROUPS },
];

const FIELD_TYPES = [
  { value: 'TEXT',     label: 'Текст',        icon: 'T' },
  { value: 'TEXTAREA', label: 'Длинный текст', icon: '≡' },
  { value: 'NUMBER',   label: 'Число',         icon: '#' },
  { value: 'SELECT',   label: 'Выбор',         icon: '▾' },
  { value: 'CHECKBOX', label: 'Флажок',        icon: '☑' },
  { value: 'PHOTO',    label: 'Фото',          icon: '📷' },
];

const VEHICLE_TYPES = ['SEDAN', 'SUV', 'TRUCK', 'VAN', 'MOTORCYCLE', 'OTHER'];

interface TItem {
  id: number; title: string; description?: string;
  fieldType: string; isRequired: boolean;
  hasPhoto?: boolean; hasVideo?: boolean; hasRating?: boolean; sortOrder?: number | null;
}
interface TSection { id: number; name: string; sortOrder: number; items: TItem[]; }
interface Template { id: number; name: string; vehicleType: string; isActive: boolean; groups?: TSection[]; }

function TemplatesPage() {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [selected, setSelected]   = useState<Template | null>(null);
  const [loading, setLoading]     = useState(true);
  const [msg, setMsg]             = useState('');
  const [msgErr, setMsgErr]       = useState(false);
  const [tplSearch, setTplSearch] = useState('');
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
  // Scenario 13.8: an item may require a video (e.g. "Работа двигателя": rating + video); the
  // diagnostician must then upload it before the report can be submitted.
  const [itemHasVideo, setItemHasVideo] = useState(false);
  const [seeding, setSeeding]       = useState(false);
  const [seedProgress, setSeedProgress] = useState('');
  const [confirmSeed, setConfirmSeed] = useState(false);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); setTimeout(() => setMsg(''), 3000); };

  const seedAllTemplates = async () => {
    setSeeding(true);
    let created = 0; let errored = 0;
    for (const tpl of SEED_TEMPLATES) {
      try {
        setSeedProgress(`${tpl.vehicleType}…`);
        const tplRes = await api.post('/admin/diagnostic/templates', {
          name: tpl.name, vehicleType: tpl.vehicleType, isActive: true,
        });
        const tplId: number = tplRes.data.id;
        for (let gi = 0; gi < tpl.groups.length; gi++) {
          const grp = tpl.groups[gi];
          const grpRes = await api.post(`/admin/diagnostic/templates/${tplId}/groups`, {
            name: grp.name, sortOrder: gi + 1,
          });
          const grpId: number = grpRes.data.id;
          for (let ii = 0; ii < grp.items.length; ii++) {
            const it = grp.items[ii];
            await api.post(`/admin/diagnostic/groups/${grpId}/items`, {
              title: it.title,
              description: it.description || null,
              fieldType: it.fieldType,
              isRequired: it.isRequired,
              hasPhoto: it.fieldType === 'PHOTO',
              hasRating: it.fieldType === 'NUMBER',
              hasVideo: false,
              sortOrder: ii + 1,
            });
          }
        }
        created++;
      } catch { errored++; }
    }
    setSeeding(false); setSeedProgress('');
    notify(
      errored > 0
        ? `Создано ${created} из ${SEED_TEMPLATES.length} шаблонов (ошибок: ${errored})`
        : `Создано ${created} шаблонов для всех типов кузовов`,
      errored > 0
    );
    loadTemplates();
  };

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
      t.name.toLowerCase().includes(q) || t.vehicleType?.toLowerCase().includes(q)
    );
  }, [templates, tplSearch]);

  const createTemplate = async () => {
    if (!tplName.trim()) return;
    try {
      await api.post('/admin/diagnostic/templates', { name: tplName.trim(), vehicleType: tplType, isActive: true });
      notify('Шаблон создан'); setTplName(''); setShowTplForm(false); loadTemplates();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const toggleActive = async (t: Template) => {
    try {
      await api.put(`/admin/diagnostic/templates/${t.id}`, { name: t.name, vehicleType: t.vehicleType, isActive: !t.isActive });
      notify(`Шаблон ${t.isActive ? 'деактивирован' : 'активирован'}`);
      loadTemplates();
      if (selected?.id === t.id) loadTemplate(t.id);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  // Scenario 14.4: "Копировать" -> new name -> an exact, inactive copy opens in the constructor.
  const [copySource, setCopySource] = useState<Template | null>(null);
  const [copyName, setCopyName] = useState('');
  const [copying, setCopying] = useState(false);

  const copyTemplate = async () => {
    if (!copySource || !copyName.trim()) return;
    setCopying(true);
    try {
      const r = await api.post(`/admin/diagnostic/templates/${copySource.id}/copy`, { name: copyName.trim() });
      notify(`Шаблон «${copySource.name}» скопирован как «${r.data.name}» (неактивен)`);
      setCopySource(null); setCopyName('');
      loadTemplates();
      setSelected(r.data);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка копирования', true); }
    finally { setCopying(false); }
  };

  const deleteTemplate = async (id: number) => {
    try {
      await api.delete(`/admin/diagnostic/templates/${id}`);
      notify('Шаблон удалён');
      if (selected?.id === id) setSelected(null);
      loadTemplates();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const addSection = async () => {
    if (!selected || !sectionName.trim()) return;
    try {
      await api.post(`/admin/diagnostic/templates/${selected.id}/groups`, {
        name: sectionName.trim(), sortOrder: (selected.groups?.length ?? 0) + 1,
      });
      setSectionName(''); setShowSectionForm(false); loadTemplate(selected.id);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const deleteSection = async (groupId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/groups/${groupId}`); loadTemplate(selected.id); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  // Scenario 14.3: deleting a group removes every item in it - ask first.
  const [confirmDeleteSection, setConfirmDeleteSection] = useState<number | null>(null);

  // Scenario 14.2: ✏️ next to an item opens an edit modal (title, description, required).
  // The PUT replaces every item attribute, so type, media/rating flags and order are sent
  // back unchanged.
  const [editItem, setEditItem] = useState<TItem | null>(null);
  const [savingItem, setSavingItem] = useState(false);

  const saveItem = async () => {
    if (!editItem || !editItem.title.trim() || !selected) return;
    setSavingItem(true);
    try {
      await api.put(`/admin/diagnostic/items/${editItem.id}`, {
        title: editItem.title.trim(),
        description: editItem.description?.trim() || null,
        fieldType: editItem.fieldType,
        isRequired: editItem.isRequired,
        hasPhoto: !!editItem.hasPhoto,
        hasVideo: !!editItem.hasVideo,
        hasRating: !!editItem.hasRating,
        sortOrder: editItem.sortOrder ?? null,
      });
      notify('Пункт сохранён');
      setEditItem(null);
      loadTemplate(selected.id);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
    finally { setSavingItem(false); }
  };

  // Scenario 13: groups and items are reordered by drag & drop (or the ↑/↓ buttons) and the order
  // is persisted in sort_order. The moved list is renumbered 1..n so older rows that all share
  // the same sort_order still end up in a well-defined order.
  const [dragGroup, setDragGroup] = useState<number | null>(null);
  const [dragItem, setDragItem] = useState<{ groupId: number; index: number } | null>(null);

  const moved = <T,>(list: T[], from: number, to: number): T[] => {
    const next = [...list];
    const [entry] = next.splice(from, 1);
    next.splice(to, 0, entry);
    return next;
  };

  const reorderGroups = async (from: number, to: number) => {
    if (!selected?.groups || from === to || to < 0 || to >= selected.groups.length) return;
    const groups = moved(selected.groups, from, to);
    setSelected({ ...selected, groups });
    try {
      await Promise.all(groups.map((g, i) => g.sortOrder === i + 1 ? null
        : api.put(`/admin/diagnostic/groups/${g.id}`, { name: g.name, sortOrder: i + 1 })));
    } catch (e: any) { notify(e.response?.data?.message || 'Не удалось сохранить порядок', true); }
    loadTemplate(selected.id);
  };

  const reorderItems = async (groupId: number, from: number, to: number) => {
    const group = selected?.groups?.find(g => g.id === groupId);
    if (!selected || !group || from === to || to < 0 || to >= group.items.length) return;
    const items = moved(group.items, from, to);
    setSelected({ ...selected, groups: selected.groups!.map(g => g.id === groupId ? { ...g, items } : g) });
    try {
      await Promise.all(items.map((it, i) => it.sortOrder === i + 1 ? null
        : api.put(`/admin/diagnostic/items/${it.id}`, {
          title: it.title, description: it.description ?? null, fieldType: it.fieldType, isRequired: it.isRequired,
          hasPhoto: !!it.hasPhoto, hasVideo: !!it.hasVideo, hasRating: !!it.hasRating, sortOrder: i + 1,
        })));
    } catch (e: any) { notify(e.response?.data?.message || 'Не удалось сохранить порядок', true); }
    loadTemplate(selected.id);
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
        hasVideo: itemHasVideo,
        // new items go to the end of their group
        sortOrder: (selected?.groups?.find(g => g.id === groupId)?.items?.length ?? 0) + 1,
      });
      setItemTitle(''); setItemDesc(''); setItemRequired(true); setItemHasVideo(false);
      setItemFieldType('TEXT'); setActiveSection(null);
      if (selected) loadTemplate(selected.id);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const deleteField = async (itemId: number) => {
    if (!selected) return;
    try { await api.delete(`/admin/diagnostic/items/${itemId}`); loadTemplate(selected.id); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const ftMeta = (ft: string) => FIELD_TYPES.find(x => x.value === ft) || { label: ft, icon: '?' };

  return (
    <div className="space-y-4">
      {confirmSeed && (
        <ConfirmModal
          message={`Создать стандартные шаблоны для всех ${SEED_TEMPLATES.length} типов кузовов? Существующие шаблоны не будут изменены.`}
          onYes={() => { setConfirmSeed(false); seedAllTemplates(); }}
          onNo={() => setConfirmSeed(false)}
        />
      )}

      {confirmDeleteSection != null && (
        <ConfirmModal
          message="Удалить группу и все пункты внутри?"
          onYes={() => { const id = confirmDeleteSection; setConfirmDeleteSection(null); deleteSection(id); }}
          onNo={() => setConfirmDeleteSection(null)}
        />
      )}

      {editItem && (
        <Modal onClose={() => setEditItem(null)}>
          <div className="p-5 space-y-3">
            <h3 className="font-semibold text-gray-900">Редактировать пункт</h3>
            <input value={editItem.title} onChange={e => setEditItem({ ...editItem, title: e.target.value })}
              aria-label="Название пункта" placeholder="Название пункта *"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
            <input value={editItem.description ?? ''} onChange={e => setEditItem({ ...editItem, description: e.target.value })}
              aria-label="Описание пункта" placeholder="Описание / подсказка (необязательно)"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <input type="checkbox" checked={editItem.isRequired}
                onChange={e => setEditItem({ ...editItem, isRequired: e.target.checked })} />
              <span className="text-gray-700">Обязательный пункт</span>
            </label>
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <input type="checkbox" checked={!!editItem.hasVideo}
                onChange={e => setEditItem({ ...editItem, hasVideo: e.target.checked })} />
              <span className="text-gray-700">Требовать видео</span>
            </label>
            <div className="flex gap-2 justify-end">
              <button onClick={() => setEditItem(null)} disabled={savingItem}
                className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg">Отмена</button>
              <button onClick={saveItem} disabled={savingItem || !editItem.title.trim()}
                className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg disabled:opacity-50">
                {savingItem ? 'Сохранение...' : 'Сохранить'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      {copySource && (
        <Modal onClose={() => setCopySource(null)}>
          <div className="p-5 space-y-3">
            <h3 className="font-semibold text-gray-900">Новое название шаблона</h3>
            <p className="text-xs text-gray-500">
              Будет создана точная копия шаблона «{copySource.name}» со всеми группами и пунктами. Копия создаётся неактивной.
            </p>
            <input value={copyName} onChange={e => setCopyName(e.target.value)} placeholder="Название нового шаблона"
              aria-label="Название нового шаблона"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
            <div className="flex gap-2 justify-end">
              <button onClick={() => setCopySource(null)} disabled={copying}
                className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg">Отмена</button>
              <button onClick={copyTemplate} disabled={copying || !copyName.trim()}
                className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg disabled:opacity-50">
                {copying ? 'Копирование...' : 'Сохранить копию'}
              </button>
            </div>
          </div>
        </Modal>
      )}

      <div>
        <h2 className="text-xl font-bold text-gray-900">Шаблоны диагностики</h2>
        <p className="text-sm text-gray-500 mt-0.5">Конструктор анкет для диагностических отчётов</p>
      </div>

      <Alert msg={msg} err={msgErr} />

      {seeding && (
        <div className="flex items-center gap-2 text-sm text-blue-700 bg-blue-50 border border-blue-100 rounded-xl px-4 py-3">
          <svg className="w-4 h-4 animate-spin shrink-0" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
          </svg>
          Создание шаблонов: {seedProgress}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Template list */}
        <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
          <div className="px-4 py-3 border-b border-gray-100 bg-gray-50 flex justify-between items-center">
            <span className="font-semibold text-sm text-gray-800">Шаблоны</span>
            <div className="flex gap-1.5">
              <button onClick={() => setConfirmSeed(true)} disabled={seeding}
                title="Создать стандартные шаблоны для всех типов кузовов"
                className="text-xs bg-green-600 text-white px-2.5 py-1.5 rounded-lg hover:bg-green-700 disabled:opacity-50">
                🌱 Базовые
              </button>
              <button onClick={() => setShowTplForm(v => !v)}
                className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg hover:bg-blue-700">+ Новый</button>
            </div>
          </div>

          {showTplForm && (
            <div className="p-3 border-b border-gray-100 space-y-2 bg-blue-50">
              <input value={tplName} onChange={e => setTplName(e.target.value)}
                placeholder="Название шаблона" className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              <select value={tplType} onChange={e => setTplType(e.target.value)}
                className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm">
                {VEHICLE_TYPES.map(t => <option key={t}>{t}</option>)}
              </select>
              <div className="flex gap-2">
                <button onClick={createTemplate} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg">Создать</button>
                <button onClick={() => setShowTplForm(false)} className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg">Отмена</button>
              </div>
            </div>
          )}

          <div className="p-2 border-b border-gray-100">
            <SearchInput value={tplSearch} onChange={setTplSearch} placeholder="Поиск шаблона..." />
          </div>

          {loading ? (
            <div className="p-6 text-center text-gray-400 text-sm">Загрузка...</div>
          ) : filteredTemplates.length === 0 ? (
            <div className="p-6 text-center text-gray-400 text-sm">Нет шаблонов</div>
          ) : (
            <div className="divide-y divide-gray-50 max-h-[500px] overflow-y-auto">
              {filteredTemplates.map(t => (
                <div key={t.id}
                  className={`px-3 py-2.5 cursor-pointer hover:bg-gray-50 transition-colors ${selected?.id === t.id ? 'bg-blue-50 border-l-2 border-blue-500' : ''}`}
                  onClick={() => loadTemplate(t.id)}>
                  <div className="flex justify-between items-start">
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate text-gray-900">{t.name}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{t.vehicleType}</p>
                    </div>
                    <div className="flex items-center gap-1 ml-2 shrink-0">
                      <button onClick={e => { e.stopPropagation(); toggleActive(t); }}
                        className={`text-xs px-1.5 py-0.5 rounded ${t.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                        {t.isActive ? '✓' : '○'}
                      </button>
                      <button onClick={e => { e.stopPropagation(); setCopySource(t); setCopyName(''); }}
                        title="Копировать" aria-label={`Копировать шаблон ${t.name}`}
                        className="text-xs text-gray-400 hover:text-blue-600 px-1">⧉</button>
                      <button onClick={e => { e.stopPropagation(); deleteTemplate(t.id); }}
                        className="text-xs text-red-400 hover:text-red-600 px-1">✕</button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Constructor */}
        <div className="md:col-span-2">
          {!selected ? (
            <div className="bg-white border border-gray-100 rounded-2xl p-8 text-center text-gray-400 text-sm shadow-sm">
              <div className="text-4xl mb-3">📋</div>
              Выберите шаблон для редактирования
            </div>
          ) : (
            <div className="bg-white border border-gray-100 rounded-2xl shadow-sm overflow-hidden">
              <div className="px-4 py-3 border-b border-gray-100 bg-gray-50 flex justify-between items-center">
                <div>
                  <span className="font-semibold text-gray-900">{selected.name}</span>
                  <span className="text-xs text-gray-400 ml-2">{selected.vehicleType}</span>
                  <span className={`ml-2 text-xs px-2 py-0.5 rounded-full ${selected.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                    {selected.isActive ? 'Активен' : 'Неактивен'}
                  </span>
                </div>
                <button onClick={() => setShowSectionForm(v => !v)}
                  className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg hover:bg-blue-700">+ Раздел</button>
              </div>

              {showSectionForm && (
                <div className="p-3 border-b border-gray-100 flex gap-2 bg-blue-50">
                  <input value={sectionName} onChange={e => setSectionName(e.target.value)}
                    placeholder="Название раздела" className="flex-1 border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                  <button onClick={addSection} className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg">Добавить</button>
                  <button onClick={() => setShowSectionForm(false)} className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg">✕</button>
                </div>
              )}

              <div className="divide-y divide-gray-50 max-h-[550px] overflow-y-auto">
                {(!selected.groups || selected.groups.length === 0) && (
                  <p className="text-center text-gray-400 py-8 text-sm">Нет разделов. Добавьте первый раздел.</p>
                )}
                {(selected.groups || []).map((section, sIdx) => (
                  <div key={section.id} className={`p-4 ${dragGroup === sIdx ? 'opacity-50' : ''}`}
                    data-testid={`group-${section.id}`}
                    draggable onDragStart={() => setDragGroup(sIdx)} onDragEnd={() => setDragGroup(null)}
                    onDragOver={e => { if (dragGroup != null) e.preventDefault(); }}
                    onDrop={() => { if (dragGroup != null) { reorderGroups(dragGroup, sIdx); setDragGroup(null); } }}>
                    <div className="flex justify-between items-center mb-3">
                      <div className="flex items-center gap-2">
                        <span className="w-5 h-5 bg-blue-100 text-blue-600 rounded-lg text-xs flex items-center justify-center font-bold">{sIdx + 1}</span>
                        <span className="font-medium text-sm text-gray-900">{section.name}</span>
                        <span className="text-xs text-gray-400">{section.items?.length || 0} полей</span>
                      </div>
                      <div className="flex gap-2">
                        <button onClick={() => reorderGroups(sIdx, sIdx - 1)} disabled={sIdx === 0}
                          aria-label={`Группа ${section.name} выше`}
                          className="text-xs text-gray-400 hover:text-gray-700 px-1 disabled:opacity-30">↑</button>
                        <button onClick={() => reorderGroups(sIdx, sIdx + 1)} disabled={sIdx === (selected.groups || []).length - 1}
                          aria-label={`Группа ${section.name} ниже`}
                          className="text-xs text-gray-400 hover:text-gray-700 px-1 disabled:opacity-30">↓</button>
                        <button onClick={() => setActiveSection(activeSection === section.id ? null : section.id)}
                          className="text-xs bg-blue-50 text-blue-600 px-2.5 py-1 rounded-lg hover:bg-blue-100">+ Поле</button>
                        <button onClick={() => setConfirmDeleteSection(section.id)}
                          className="text-xs bg-red-50 text-red-500 px-2 py-1 rounded-lg hover:bg-red-100">Удалить</button>
                      </div>
                    </div>

                    {activeSection === section.id && (
                      <div className="bg-gray-50 rounded-xl p-3 mb-3 space-y-3 border border-gray-100">
                        <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide">Новое поле</p>
                        <input value={itemTitle} onChange={e => setItemTitle(e.target.value)}
                          placeholder="Название поля *" className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
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
                        {itemFieldType === 'SELECT' ? (
                          <div>
                            <p className="text-xs text-gray-500 mb-1">Варианты (каждый с новой строки)</p>
                            <textarea value={itemDesc} onChange={e => setItemDesc(e.target.value)}
                              placeholder="Вариант 1&#10;Вариант 2&#10;Вариант 3" rows={3}
                              className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm resize-none" />
                          </div>
                        ) : (
                          <input value={itemDesc} onChange={e => setItemDesc(e.target.value)}
                            placeholder="Описание / подсказка (необязательно)"
                            className="w-full border border-gray-200 rounded-lg px-2 py-1.5 text-sm" />
                        )}
                        <label className="flex items-center gap-2 text-xs cursor-pointer">
                          <input type="checkbox" checked={itemRequired} onChange={e => setItemRequired(e.target.checked)} />
                          <span className="text-gray-600">Обязательное поле</span>
                        </label>
                        <label className="flex items-center gap-2 text-xs cursor-pointer">
                          <input type="checkbox" checked={itemHasVideo} onChange={e => setItemHasVideo(e.target.checked)} />
                          <span className="text-gray-600">Требовать видео</span>
                        </label>
                        <div className="flex gap-2">
                          <button onClick={() => addField(section.id)}
                            className="text-xs bg-blue-600 text-white px-3 py-1.5 rounded-lg hover:bg-blue-700">Добавить поле</button>
                          <button onClick={() => setActiveSection(null)}
                            className="text-xs border border-gray-200 px-3 py-1.5 rounded-lg text-gray-600">Отмена</button>
                        </div>
                      </div>
                    )}

                    <div className="space-y-1.5">
                      {(!section.items || section.items.length === 0) && (
                        <p className="text-xs text-gray-400 text-center py-2">Нет полей в разделе</p>
                      )}
                      {(section.items || []).map((field, fIdx) => {
                        const ft = ftMeta(field.fieldType || 'TEXT');
                        return (
                          <div key={field.id} data-testid={`item-${field.id}`}
                            draggable
                            onDragStart={e => { e.stopPropagation(); setDragItem({ groupId: section.id, index: fIdx }); }}
                            onDragEnd={() => setDragItem(null)}
                            onDragOver={e => { if (dragItem?.groupId === section.id) { e.preventDefault(); e.stopPropagation(); } }}
                            onDrop={e => {
                              if (dragItem?.groupId !== section.id) return;
                              e.stopPropagation();
                              reorderItems(section.id, dragItem.index, fIdx);
                              setDragItem(null);
                            }}
                            className="flex justify-between items-center bg-gray-50 rounded-xl px-3 py-2 text-sm border border-gray-100">
                            <div className="flex items-center gap-2 min-w-0">
                              <span className="text-gray-400 text-xs w-5 text-right shrink-0">{fIdx + 1}.</span>
                              <span className="text-xs px-1.5 py-0.5 bg-white border border-gray-200 rounded-lg text-gray-500 shrink-0 font-mono">{ft.icon}</span>
                              <div className="min-w-0">
                                <span className="text-sm">{field.title}</span>
                                {field.isRequired && <span className="text-red-500 ml-1">*</span>}
                                {field.hasVideo && <span className="ml-1 text-xs" title="Требуется видео">🎥</span>}
                                {field.description && <p className="text-xs text-gray-400 truncate mt-0.5">{field.description}</p>}
                              </div>
                            </div>
                            <div className="flex items-center gap-2 ml-3 shrink-0">
                              <span className="text-xs text-gray-400 hidden sm:block">{ft.label}</span>
                              <button onClick={() => reorderItems(section.id, fIdx, fIdx - 1)} disabled={fIdx === 0}
                                aria-label={`Пункт ${field.title} выше`}
                                className="text-gray-400 hover:text-gray-700 text-xs px-1 disabled:opacity-30">↑</button>
                              <button onClick={() => reorderItems(section.id, fIdx, fIdx + 1)} disabled={fIdx === (section.items || []).length - 1}
                                aria-label={`Пункт ${field.title} ниже`}
                                className="text-gray-400 hover:text-gray-700 text-xs px-1 disabled:opacity-30">↓</button>
                              <button onClick={() => setEditItem({ ...field })}
                                title="Редактировать" aria-label={`Редактировать пункт ${field.title}`}
                                className="text-gray-400 hover:text-blue-600 text-xs px-1">✏️</button>
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

// ─── Auctions ─────────────────────────────────────────────────────────────────

function AuctionsPage() {
  const navigate = useNavigate();
  const [allAuctions, setAllAuctions] = useState<Auction[]>([]);
  const [loading, setLoading]         = useState(true);
  const [msg, setMsg]                 = useState('');
  const [msgErr, setMsgErr]           = useState(false);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch]           = useState('');
  const [showCreate, setShowCreate]   = useState(false);
  const [vehicles, setVehicles]       = useState<any[]>([]);
  const [cancelTarget, setCancelTarget] = useState<number | null>(null);
  const [userMap, setUserMap] = useState<Record<number, string>>({});
  const [form, setForm] = useState({
    vehicleId: '', startingPrice: '', minBidStep: '',
    startTime: '', endTime: '', buyNowPrice: '', reservePrice: '',
  });
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [auctionPhotos, setAuctionPhotos] = useState<string[]>([]);
  const [photoUploading, setPhotoUploading] = useState(false);
  const [releasedAuctions, setReleasedAuctions] = useState<Set<number>>(new Set());
  const [auctionDetail, setAuctionDetail] = useState<Auction | null>(null);
  const [detailParticipants, setDetailParticipants] = useState<any[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); setTimeout(() => setMsg(''), 4000); };

  const loadAuctions = () => {
    setLoading(true);
    api.get('/admin/auctions?size=200&sort=id,desc')
      .then(r => setAllAuctions(r.data.content || []))
      .catch(() => notify('Ошибка загрузки аукционов', true))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadAuctions();
    api.get('/admin/vehicles/approved')
      .then(r => setVehicles(Array.isArray(r.data) ? r.data : (r.data.content || [])))
      .catch(() => {});
    api.get('/admin/users?size=500')
      .then(r => {
        const list: any[] = Array.isArray(r.data) ? r.data : (r.data.content || []);
        setUserMap(Object.fromEntries(list.map((u: any) => [u.id, u.username])));
      })
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
    try { await api.post(`/admin/auctions/${id}/confirm-start`); notify(`Аукцион #${id} подтверждён`); loadAuctions(); }
    catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const cancelAuction = async () => {
    if (cancelTarget == null) return;
    try {
      await api.post(`/admin/auctions/${cancelTarget}/cancel`);
      notify(`Аукцион #${cancelTarget} отменён`);
      setCancelTarget(null); loadAuctions();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); setCancelTarget(null); }
  };

  // Guards the detail panel's participant list (and their frozen deposits) against stale
  // responses - an earlier auction's participants, or a force-unfreeze refresh, resolving after
  // the admin opened another auction. Every openDetail() bumps the generation (and resets the
  // list); only a response whose captured generation is still current may update the panel
  // (same pattern as ReportsPage's detailGeneration). Closing needs no bump: the panel is hidden
  // and the next openDetail() supersedes anything still in flight.
  const participantsGeneration = useRef(0);

  const openDetail = async (auction: Auction) => {
    const generation = ++participantsGeneration.current;
    setAuctionDetail(auction);
    setDetailLoading(true);
    setDetailParticipants([]);
    try {
      const r = await api.get(`/admin/auctions/${auction.id}/participants`);
      if (generation === participantsGeneration.current) setDetailParticipants(r.data);
    } catch {} finally { if (generation === participantsGeneration.current) setDetailLoading(false); }
  };

  const forceUnfreeze = async (auctionId: number) => {
    const generation = participantsGeneration.current;
    try {
      const r = await api.post(`/admin/auctions/${auctionId}/force-unfreeze-all`);
      notify(r.data.message || 'Разморозка выполнена');
      const r2 = await api.get(`/admin/auctions/${auctionId}/participants`);
      if (generation === participantsGeneration.current) setDetailParticipants(r2.data);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка при разморозке', true); }
  };

  const movePhoto = (i: number, dir: -1 | 1) => {
    const j = i + dir;
    setAuctionPhotos(prev => {
      if (j < 0 || j >= prev.length) return prev;
      const arr = [...prev];
      [arr[i], arr[j]] = [arr[j], arr[i]];
      return arr;
    });
  };

  const releaseWinner = async (auctionId: number) => {
    try {
      await api.post(`/admin/auctions/${auctionId}/release-winner`);
      setReleasedAuctions(prev => new Set([...prev, auctionId]));
      notify(`Депозит победителя аукциона #${auctionId} освобождён`);
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка при освобождении депозита', true); }
  };

  const validateForm = () => {
    const errs: Record<string, string> = {};
    if (!form.vehicleId) errs.vehicleId = 'Выберите автомобиль';
    // Scenarios 5.3 / 17.2: the main photo (the first one) is mandatory.
    if (auctionPhotos.length === 0) errs.photos = 'Загрузите главное фото (обязательно)';
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

  const uploadPhoto = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || []);
    if (!files.length) return;
    if (auctionPhotos.length + files.length > 20) { notify('Максимум 20 фотографий', true); return; }
    setPhotoUploading(true);
    try {
      const token = localStorage.getItem('accessToken');
      const headers = token ? { Authorization: `Bearer ${token}` } : {};
      const urls: string[] = [];
      for (const file of files) {
        const fd = new FormData();
        fd.append('file', file);
        const res = await axios.post(`${BASE_URL}/api/media/upload`, fd, { headers });
        urls.push(res.data.publicUrl);
      }
      setAuctionPhotos(prev => [...prev, ...urls]);
    } catch (e: any) {
      notify(e.response?.data?.error || 'Ошибка загрузки фото', true);
    } finally {
      setPhotoUploading(false);
      e.target.value = '';
    }
  };

  const createAuction = async () => {
    const errs = validateForm();
    if (Object.keys(errs).length > 0) { setFormErrors(errs); return; }
    setFormErrors({});
    try {
      const mainPhotoUrl = auctionPhotos[0] || null;
      const additionalPhotoUrls = auctionPhotos.length > 1 ? JSON.stringify(auctionPhotos.slice(1)) : null;
      await api.post('/admin/auctions', {
        vehicleId: parseInt(form.vehicleId),
        startingPrice: parseFloat(form.startingPrice),
        minBidStep: parseFloat(form.minBidStep),
        startTime: new Date(form.startTime).toISOString(),
        endTime: new Date(form.endTime).toISOString(),
        buyNowPrice: form.buyNowPrice ? parseFloat(form.buyNowPrice) : null,
        reservePrice: form.reservePrice ? parseFloat(form.reservePrice) : null,
        mainPhotoUrl,
        additionalPhotoUrls,
      });
      notify('Аукцион создан');
      setShowCreate(false);
      setForm({ vehicleId: '', startingPrice: '', minBidStep: '', startTime: '', endTime: '', buyNowPrice: '', reservePrice: '' });
      setAuctionPhotos([]);
      loadAuctions();
    } catch (e: any) { notify(e.response?.data?.message || 'Не удалось создать аукцион', true); }
  };

  const AUCTION_STATUS_LABELS: Record<string, string> = {
    CREATED: 'Создан', ACTIVE: 'Активен', COMPLETED: 'Завершён', CANCELLED: 'Отменён',
  };

  return (
    <div className="space-y-4">
      {cancelTarget != null && (
        <ConfirmModal
          message={`Отменить аукцион #${cancelTarget}? Действие необратимо.`}
          onYes={cancelAuction} onNo={() => setCancelTarget(null)}
        />
      )}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Аукционы</h2>
          <p className="text-sm text-gray-500 mt-0.5">Всего: {allAuctions.length}</p>
        </div>
        <button onClick={() => setShowCreate(!showCreate)}
          className="bg-blue-600 text-white px-4 py-2 rounded-xl text-sm font-medium hover:bg-blue-700 transition-colors">
          + Создать аукцион
        </button>
      </div>

      <Alert msg={msg} err={msgErr} />

      {showCreate && (
        <div className="bg-white border border-gray-100 rounded-2xl p-5 shadow-sm space-y-4">
          <h4 className="font-semibold text-gray-900">Новый аукцион</h4>
          {vehicles.length === 0 ? (
            <div className="p-4 bg-amber-50 border border-amber-200 rounded-xl text-sm text-amber-800">
              <p className="font-medium">Нет доступных автомобилей</p>
              <p className="mt-1 text-amber-700">Для создания аукциона необходим автомобиль с одобрённым диагностическим отчётом. Сначала создайте диагностическую заявку, пройдите диагностику и дождитесь одобрения.</p>
            </div>
          ) : (
            <div className="space-y-4">
              <div>
                <label className="text-xs text-gray-500 mb-1 block font-medium">Автомобиль *</label>
                <select value={form.vehicleId} onChange={e => setForm({...form, vehicleId: e.target.value})}
                  className={`w-full border rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${formErrors.vehicleId ? 'border-red-400' : 'border-gray-200'}`}>
                  <option value="">— Выберите автомобиль (только с APPROVED отчётом) —</option>
                  {vehicles.map((v: any) => (
                    <option key={v.id} value={v.id}>{v.brand} {v.model} {v.year} — VIN: {v.vin}</option>
                  ))}
                </select>
                {formErrors.vehicleId && <p className="text-red-500 text-xs mt-0.5">{formErrors.vehicleId}</p>}
              </div>
              <div className="grid grid-cols-2 gap-3">
                {[
                  { f: 'startingPrice', label: 'Стартовая цена *' },
                  { f: 'minBidStep',    label: 'Шаг ставки *' },
                  { f: 'reservePrice',  label: 'Резервная цена (опц.)' },
                  { f: 'buyNowPrice',   label: 'Buy Now (опц.)' },
                ].map(({ f, label }) => (
                  <div key={f}>
                    <label className="text-xs text-gray-500 mb-1 block">{label}</label>
                    <input type="number" placeholder="0" min={0} max={999999999}
                      value={(form as any)[f]}
                      onChange={e => setForm({...form, [f]: e.target.value})}
                      className={`w-full border rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${(formErrors as any)[f] ? 'border-red-400' : 'border-gray-200'}`} />
                    {(formErrors as any)[f] && <p className="text-red-500 text-xs mt-0.5">{(formErrors as any)[f]}</p>}
                  </div>
                ))}
                <div>
                  <label className="text-xs text-gray-500 mb-1 block">Дата начала *</label>
                  <input type="datetime-local" value={form.startTime}
                    onChange={e => setForm({...form, startTime: e.target.value})}
                    className={`w-full border rounded-xl px-3 py-2 text-sm ${formErrors.startTime ? 'border-red-400' : 'border-gray-200'}`} />
                  {formErrors.startTime && <p className="text-red-500 text-xs mt-0.5">{formErrors.startTime}</p>}
                </div>
                <div>
                  <label className="text-xs text-gray-500 mb-1 block">Дата окончания *</label>
                  <input type="datetime-local" value={form.endTime}
                    onChange={e => setForm({...form, endTime: e.target.value})}
                    className={`w-full border rounded-xl px-3 py-2 text-sm ${formErrors.endTime ? 'border-red-400' : 'border-gray-200'}`} />
                  {formErrors.endTime && <p className="text-red-500 text-xs mt-0.5">{formErrors.endTime}</p>}
                </div>
              </div>

              <div>
                <label className="text-xs text-gray-500 mb-1 block font-medium">
                  Фотографии * (первая — главная) {auctionPhotos.length > 0 && `— загружено: ${auctionPhotos.length}`}
                </label>
                {formErrors.photos && <p className="text-red-500 text-xs mb-1">{formErrors.photos}</p>}
                {auctionPhotos.length > 0 && (
                  <div className="flex flex-wrap gap-2 mb-2">
                    {auctionPhotos.map((url, i) => (
                      <div key={i} className="relative flex flex-col items-center gap-1">
                        <div className="relative">
                          <img src={url} alt="" className="w-16 h-16 object-cover rounded-xl border border-gray-100" />
                          {i === 0 && <span className="absolute -top-1 -left-1 bg-blue-600 text-white text-xs rounded-lg px-1">Гл.</span>}
                          <button type="button"
                            onClick={() => setAuctionPhotos(prev => prev.filter((_, j) => j !== i))}
                            className="absolute -top-1 -right-1 bg-red-500 text-white rounded-full w-4 h-4 text-xs flex items-center justify-center">✕</button>
                        </div>
                        <div className="flex gap-0.5">
                          <button type="button" onClick={() => movePhoto(i, -1)} disabled={i === 0}
                            className="w-7 h-5 text-xs bg-gray-100 hover:bg-gray-200 rounded disabled:opacity-30">←</button>
                          <button type="button" onClick={() => movePhoto(i, 1)} disabled={i === auctionPhotos.length - 1}
                            className="w-7 h-5 text-xs bg-gray-100 hover:bg-gray-200 rounded disabled:opacity-30">→</button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                <label className={`inline-flex items-center gap-2 cursor-pointer px-3 py-2 border rounded-xl text-sm transition-colors ${photoUploading ? 'opacity-50 pointer-events-none' : 'hover:bg-gray-50'} border-gray-200`}>
                  <span>📷</span>
                  <span>{photoUploading ? 'Загрузка...' : 'Добавить фото'}</span>
                  <input type="file" accept="image/*" multiple className="hidden"
                    onChange={uploadPhoto} disabled={photoUploading || auctionPhotos.length >= 20} />
                </label>
              </div>

              <div className="flex gap-2 pt-2 border-t border-gray-100">
                <button onClick={createAuction} className="bg-blue-600 text-white px-5 py-2 rounded-xl text-sm font-medium hover:bg-blue-700">Создать</button>
                <button onClick={() => { setShowCreate(false); setFormErrors({}); setAuctionPhotos([]); }}
                  className="border border-gray-200 px-4 py-2 rounded-xl text-sm hover:bg-gray-50">Отмена</button>
              </div>
            </div>
          )}
        </div>
      )}

      <div className="flex flex-col sm:flex-row gap-3">
        <div className="flex gap-2 flex-wrap">
          {AUCTION_STATUSES.map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                statusFilter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}>
              {s === 'ALL' ? `Все (${counts.ALL})` : `${AUCTION_STATUS_LABELS[s] || s} (${counts[s] || 0})`}
            </button>
          ))}
        </div>
        <div className="sm:ml-auto sm:w-72">
          <SearchInput value={search} onChange={setSearch} placeholder="VIN, авто, ID..." />
        </div>
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl overflow-hidden shadow-sm">
        {loading ? (
          <div className="p-8 text-center text-gray-400">Загрузка...</div>
        ) : auctions.length === 0 ? (
          <div className="p-8 text-center text-gray-400">Аукционы не найдены</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-100">
                <tr>
                  {['ID', 'Автомобиль', 'Текущая цена', 'Победитель', 'Начало', 'Окончание', 'Статус', 'Действия'].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {auctions.map(a => (
                  <tr key={a.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-gray-400 text-xs">#{a.id}</td>
                    <td className="px-4 py-3 font-medium max-w-[160px] truncate">{a.vehicleInfo || '—'}</td>
                    <td className="px-4 py-3 whitespace-nowrap font-medium">{Number(a.currentPrice).toLocaleString('ru')} сом</td>
                    <td className="px-4 py-3 text-xs">
                      {a.winnerId ? (
                        <div>
                          <span className="text-gray-700 font-medium">
                            {userMap[a.winnerId] || `User #${a.winnerId}`}
                          </span>
                          {a.winningBid && (
                            <div className="text-gray-400">{Number(a.winningBid).toLocaleString('ru')} сом</div>
                          )}
                        </div>
                      ) : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-400 whitespace-nowrap text-xs">{formatDateTime(a.startTime)}</td>
                    <td className="px-4 py-3 text-gray-400 whitespace-nowrap text-xs">{formatDateTime(a.endTime)}</td>
                    <td className="px-4 py-3"><Badge status={a.status} /></td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col gap-1.5">
                        {a.status === 'CREATED' && !a.confirmedStart && (
                          <button onClick={() => confirmStart(a.id)}
                            className="text-xs px-2.5 py-1.5 rounded-lg bg-green-600 text-white hover:bg-green-700 font-medium whitespace-nowrap">
                            Подтвердить
                          </button>
                        )}
                        {(a.status === 'CREATED' || a.status === 'ACTIVE') && (
                          <button onClick={() => setCancelTarget(a.id)}
                            className="text-xs px-2.5 py-1.5 rounded-lg bg-red-50 text-red-600 hover:bg-red-100 font-medium whitespace-nowrap">
                            Отменить
                          </button>
                        )}
                        {a.status === 'COMPLETED' && a.winnerId && !releasedAuctions.has(a.id) && (
                          <button onClick={() => releaseWinner(a.id)}
                            className="text-xs px-2.5 py-1.5 rounded-lg bg-amber-50 text-amber-700 hover:bg-amber-100 font-medium border border-amber-200 whitespace-nowrap">
                            Освободить депозит
                          </button>
                        )}
                        {(a.status === 'WAITING_FOR_PAYMENT' || a.status === 'SOLD' || a.status === 'SALE_FAILED') && (
                          <button onClick={() => navigate(`/admin/sale/${a.id}`)}
                            className="text-xs px-2.5 py-1.5 rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 font-medium border border-blue-200 whitespace-nowrap">
                            Продажа
                          </button>
                        )}
                        <button onClick={() => openDetail(a)}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-600 hover:bg-gray-200 font-medium whitespace-nowrap">
                          Подробнее
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* ── Auction Detail Panel ── */}
      {auctionDetail && (
        <div className="fixed inset-0 z-50 flex" onClick={() => setAuctionDetail(null)}>
          <div className="flex-1 bg-black/30" />
          <div className="w-full max-w-lg bg-white shadow-2xl flex flex-col overflow-hidden"
            onClick={e => e.stopPropagation()}>
            {/* Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b bg-gray-50 shrink-0">
              <div>
                <p className="font-bold text-gray-900">Аукцион #{auctionDetail.id}</p>
                <p className="text-xs text-gray-500 mt-0.5 truncate max-w-[280px]">{auctionDetail.vehicleInfo}</p>
              </div>
              <button onClick={() => setAuctionDetail(null)}
                className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-200 text-gray-500">✕</button>
            </div>

            <div className="flex-1 overflow-y-auto p-5 space-y-5">
              {/* Info grid */}
              <div className="grid grid-cols-2 gap-3 text-sm">
                {[
                  { label: 'Статус', value: <Badge status={auctionDetail.status} /> },
                  { label: 'Текущая цена', value: `${Number(auctionDetail.currentPrice).toLocaleString('ru')} сом` },
                  { label: 'Начало', value: formatDateTime(auctionDetail.startTime) },
                  { label: 'Окончание', value: formatDateTime(auctionDetail.endTime) },
                  { label: 'VIN', value: auctionDetail.vin || '—' },
                  { label: 'Подтверждён', value: auctionDetail.confirmedStart ? 'Да' : 'Нет' },
                ].map(({ label, value }) => (
                  <div key={label} className="bg-gray-50 rounded-xl p-3">
                    <p className="text-xs text-gray-400 mb-0.5">{label}</p>
                    <div className="font-medium text-gray-900">{value}</div>
                  </div>
                ))}
              </div>

              {/* Winner */}
              {auctionDetail.winnerId && (
                <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
                  <p className="text-xs text-amber-500 mb-1 font-medium uppercase tracking-wide">Победитель</p>
                  <p className="font-semibold text-gray-900">{userMap[auctionDetail.winnerId] || `User #${auctionDetail.winnerId}`}</p>
                  {auctionDetail.winningBid && (
                    <p className="text-sm text-amber-700 mt-0.5">{Number(auctionDetail.winningBid).toLocaleString('ru')} сом</p>
                  )}
                </div>
              )}

              {/* Participants */}
              <div>
                <p className="text-sm font-semibold text-gray-700 mb-2">Участники ({detailParticipants.length})</p>
                {detailLoading ? (
                  <div className="text-center py-4 text-gray-400 text-sm">Загрузка...</div>
                ) : detailParticipants.length === 0 ? (
                  <div className="text-center py-4 text-gray-400 text-sm bg-gray-50 rounded-xl">Нет участников</div>
                ) : (
                  <div className="divide-y border rounded-xl overflow-hidden">
                    {detailParticipants.map((p: any, i: number) => (
                      <div key={i} className={`flex items-center justify-between px-4 py-3 text-sm ${p.isWinner ? 'bg-amber-50' : 'bg-white'}`}>
                        <div>
                          <span className="font-medium text-gray-900">{p.username || `User #${p.userId}`}</span>
                          {p.email && <span className="text-gray-400 text-xs ml-2">{p.email}</span>}
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-gray-600">{Number(p.frozenAmount).toLocaleString('ru')} сом</span>
                          {p.isWinner && <span className="text-xs bg-amber-200 text-amber-800 px-2 py-0.5 rounded-full font-medium">Победитель</span>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Actions */}
              <div className="space-y-2 pt-2 border-t">
                {auctionDetail.status === 'COMPLETED' && auctionDetail.winnerId && !releasedAuctions.has(auctionDetail.id) && (
                  <button onClick={() => releaseWinner(auctionDetail.id)}
                    className="w-full py-2.5 bg-amber-50 text-amber-700 border border-amber-200 rounded-xl text-sm font-medium hover:bg-amber-100">
                    Освободить депозит победителя
                  </button>
                )}
                {auctionDetail.status === 'COMPLETED' && (
                  <button onClick={() => forceUnfreeze(auctionDetail.id)}
                    className="w-full py-2.5 bg-red-50 text-red-700 border border-red-200 rounded-xl text-sm font-medium hover:bg-red-100">
                    Принудительно разморозить все (не-победители)
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Deposits ─────────────────────────────────────────────────────────────────

// The closed set of AdminAdjustmentReason values the adjust endpoint accepts.
const ADJUST_REASONS: { value: string; label: string }[] = [
  { value: 'RECONCILIATION', label: 'Сверка платежа (зачисление подтверждённого платежа)' },
  { value: 'MANUAL_REFUND', label: 'Ручной возврат' },
  { value: 'CHARGEBACK', label: 'Чарджбэк' },
  { value: 'SUPPORT_CORRECTION', label: 'Исправление по обращению' },
  { value: 'OTHER', label: 'Другое' },
];

function DepositsPage() {
  const [deposits, setDeposits]   = useState<DepositItem[]>([]);
  const [users, setUsers]         = useState<UserItem[]>([]);
  const [loading, setLoading]     = useState(true);
  const [msg, setMsg]             = useState('');
  const [msgErr, setMsgErr]       = useState(false);
  const [search, setSearch]       = useState('');
  const [adjustTarget, setAdjustTarget] = useState<DepositItem | null>(null);
  const [adjustAmount, setAdjustAmount] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjustReference, setAdjustReference] = useState('');
  // Generated once when the modal opens for a given target, reused across retries of the
  // same submit attempt (e.g. after a network error) so the backend's idempotency check
  // actually protects against double-submission. A fresh key is only generated when a new
  // adjustment is started (modal re-opened), never per HTTP request.
  const [adjustKey, setAdjustKey] = useState('');
  const [txTarget, setTxTarget]   = useState<DepositItem | null>(null);
  const [transactions, setTransactions] = useState<any[]>([]);
  const [txLoading, setTxLoading] = useState(false);

  const notify = (text: string, err = false) => { setMsg(text); setMsgErr(err); setTimeout(() => setMsg(''), 4000); };

  const load = () => {
    setLoading(true);
    Promise.all([
      api.get('/admin/deposits'),
      api.get('/admin/users'),
    ]).then(([depRes, userRes]) => {
      const deps: DepositItem[] = depRes.data || [];
      const usrs: UserItem[] = userRes.data || [];
      const userMap = Object.fromEntries(usrs.map(u => [u.id, u]));
      setDeposits(deps.map(d => ({
        ...d,
        username: userMap[d.userId]?.username,
        email: userMap[d.userId]?.email,
      })));
      setUsers(usrs);
    }).catch(() => notify('Ошибка загрузки', true))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return deposits;
    return deposits.filter(d =>
      d.username?.toLowerCase().includes(q) ||
      d.email?.toLowerCase().includes(q) ||
      String(d.userId).includes(q)
    );
  }, [deposits, search]);

  const adjustDeposit = async () => {
    if (!adjustTarget || !adjustAmount || !adjustReason || !adjustReference.trim()) return;
    const amount = parseFloat(adjustAmount);
    if (isNaN(amount)) { notify('Некорректная сумма', true); return; }
    try {
      await api.post(`/admin/deposits/${adjustTarget.userId}/adjust`,
        { amount, reason: adjustReason, reference: adjustReference.trim(), idempotencyKey: adjustKey });
      notify(`Депозит скорректирован на ${amount > 0 ? '+' : ''}${amount} сом`);
      setAdjustTarget(null); setAdjustAmount(''); setAdjustReason(''); setAdjustReference(''); load();
    } catch (e: any) { notify(e.response?.data?.message || 'Ошибка', true); }
  };

  const loadTransactions = async (d: DepositItem) => {
    setTxTarget(d); setTxLoading(true);
    try {
      const r = await api.get(`/admin/deposits/${d.userId}/transactions`);
      setTransactions(r.data || []);
    } catch { setTransactions([]); }
    finally { setTxLoading(false); }
  };

  const TX_TYPE_LABELS: Record<string, string> = {
    DEPOSIT: 'Пополнение', WITHDRAWAL: 'Вывод',
    FREEZE: 'Заморозка', UNFREEZE: 'Разморозка',
    AUCTION_BID: 'Ставка', AUCTION_WIN: 'Победа',
    ADMIN_ADJUST: 'Корректировка',
  };

  const totalBalance = deposits.reduce((s, d) => s + (d.balance || 0), 0);
  const totalBlocked = deposits.reduce((s, d) => s + (d.blockedAmount || 0), 0);

  return (
    <div className="space-y-4">
      {adjustTarget && (
        <Modal onClose={() => setAdjustTarget(null)}>
          <div className="p-6 space-y-4">
            <h3 className="font-semibold text-gray-900">
              Корректировка депозита: <span className="text-blue-600">{adjustTarget.username || `#${adjustTarget.userId}`}</span>
            </h3>
            <p className="text-xs text-gray-500">Положительная сумма — пополнение. Отрицательная — списание.</p>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-gray-500 block mb-1">Сумма *</label>
                <input type="number" placeholder="Например: 5000 или -1000"
                  value={adjustAmount} onChange={e => setAdjustAmount(e.target.value)}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              </div>
              <div>
                <label htmlFor="adjust-reason" className="text-xs text-gray-500 block mb-1">Причина *</label>
                <select id="adjust-reason" value={adjustReason} onChange={e => setAdjustReason(e.target.value)}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none">
                  <option value="">Выберите причину</option>
                  {ADJUST_REASONS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
                </select>
              </div>
              <div>
                <label htmlFor="adjust-reference" className="text-xs text-gray-500 block mb-1">Основание (ссылка) *</label>
                <input id="adjust-reference" type="text" maxLength={100}
                  placeholder="Номер платежа, обращения или документа"
                  value={adjustReference} onChange={e => setAdjustReference(e.target.value)}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none" />
              </div>
            </div>
            <div className="flex gap-2 justify-end">
              <button onClick={() => setAdjustTarget(null)} className="px-4 py-2 border border-gray-200 rounded-xl text-sm hover:bg-gray-50">Отмена</button>
              <button onClick={adjustDeposit} disabled={!adjustAmount || !adjustReason || !adjustReference.trim()}
                className="px-4 py-2 bg-blue-600 text-white rounded-xl text-sm disabled:opacity-50 hover:bg-blue-700">Применить</button>
            </div>
          </div>
        </Modal>
      )}

      {txTarget && (
        <Modal onClose={() => { setTxTarget(null); setTransactions([]); }}>
          <div className="p-6 space-y-4">
            <div className="flex justify-between items-start">
              <div>
                <h3 className="font-semibold text-gray-900">Транзакции: {txTarget.username || `#${txTarget.userId}`}</h3>
                <p className="text-xs text-gray-400 mt-0.5">Баланс: {Number(txTarget.balance).toLocaleString('ru')} сом</p>
              </div>
              <button onClick={() => { setTxTarget(null); setTransactions([]); }} className="text-gray-400 hover:text-gray-600">✕</button>
            </div>
            {txLoading ? (
              <div className="py-6 text-center text-gray-400 text-sm">Загрузка...</div>
            ) : transactions.length === 0 ? (
              <div className="py-6 text-center text-gray-400 text-sm">Нет транзакций</div>
            ) : (
              <div className="divide-y divide-gray-50 max-h-80 overflow-y-auto rounded-xl border border-gray-100">
                {transactions.map((tx: any) => (
                  <div key={tx.id} className="flex items-center justify-between px-3 py-2.5 text-sm">
                    <div>
                      <p className="font-medium text-gray-800">{TX_TYPE_LABELS[tx.type] || tx.type}</p>
                      {tx.description && <p className="text-xs text-gray-400 mt-0.5">{tx.description}</p>}
                    </div>
                    <div className="text-right">
                      <p className={`font-semibold ${tx.amount > 0 ? 'text-green-600' : 'text-red-600'}`}>
                        {tx.amount > 0 ? '+' : ''}{Number(tx.amount).toLocaleString('ru')} сом
                      </p>
                      <p className="text-xs text-gray-400">{formatDate(tx.createdAt)}</p>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </Modal>
      )}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Депозиты</h2>
          <p className="text-sm text-gray-500 mt-0.5">Управление финансовыми гарантиями пользователей</p>
        </div>
      </div>

      <Alert msg={msg} err={msgErr} />

      {/* Summary cards */}
      <div className="grid grid-cols-3 gap-3">
        <div className="bg-blue-50 border border-blue-100 rounded-2xl p-4">
          <p className="text-xs text-blue-600">Всего аккаунтов</p>
          <p className="text-2xl font-bold text-blue-700 mt-1">{deposits.length}</p>
        </div>
        <div className="bg-green-50 border border-green-100 rounded-2xl p-4">
          <p className="text-xs text-green-600">Суммарный баланс</p>
          <p className="text-2xl font-bold text-green-700 mt-1">{Number(totalBalance).toLocaleString('ru')}</p>
          <p className="text-xs text-green-500">сом</p>
        </div>
        <div className="bg-orange-50 border border-orange-100 rounded-2xl p-4">
          <p className="text-xs text-orange-600">Заморожено</p>
          <p className="text-2xl font-bold text-orange-700 mt-1">{Number(totalBlocked).toLocaleString('ru')}</p>
          <p className="text-xs text-orange-500">сом</p>
        </div>
      </div>

      <div className="sm:w-72">
        <SearchInput value={search} onChange={setSearch} placeholder="Имя пользователя, email..." />
      </div>

      <div className="bg-white border border-gray-100 rounded-2xl overflow-hidden shadow-sm">
        {loading ? (
          <div className="p-8 text-center text-gray-400">Загрузка...</div>
        ) : filtered.length === 0 ? (
          <div className="p-8 text-center text-gray-400">Депозиты не найдены</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-100">
                <tr>
                  {['Пользователь', 'Email', 'Баланс', 'Заморожено', 'Доступно', 'Статус', 'Действия'].map(h => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide whitespace-nowrap">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {filtered.map(d => (
                  <tr key={d.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 font-medium text-gray-900">{d.username || `ID: ${d.userId}`}</td>
                    <td className="px-4 py-3 text-gray-500">{d.email || '—'}</td>
                    <td className="px-4 py-3 font-mono font-semibold text-gray-900">{Number(d.balance).toLocaleString('ru')} сом</td>
                    <td className="px-4 py-3 font-mono text-orange-600">{Number(d.blockedAmount).toLocaleString('ru')} сом</td>
                    <td className="px-4 py-3 font-mono text-green-700">{Number(d.availableBalance).toLocaleString('ru')} сом</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${d.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                        {d.status === 'ACTIVE' ? 'Активен' : d.status}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1.5">
                        <button onClick={() => loadTransactions(d)}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 font-medium">
                          История
                        </button>
                        <button onClick={() => { setAdjustTarget(d); setAdjustAmount(''); setAdjustReason(''); setAdjustReference(''); setAdjustKey(crypto.randomUUID()); }}
                          className="text-xs px-2.5 py-1.5 rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 font-medium">
                          Корректировать
                        </button>
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

// ─── Settings ─────────────────────────────────────────────────────────────────

const SETTINGS_META: Record<string, { label: string; description: string; min: number; max: number; step: number }> = {
  deposit_percent:                      { label: 'Депозит для аукциона (%)',          description: 'Процент от стартовой цены, замораживаемый при входе в аукцион',          min: 0, max: 50, step: 1 },
  default_bid_step_percent:             { label: 'Минимальный шаг ставки (%)',         description: 'Минимальный шаг ставки в процентах от текущей цены аукциона',            min: 0, max: 50, step: 0.5 },
  // commission_percent / platform_fee_percent removed: commission is configured only via the
  // PolicyVersion admin surface (/admin/policy). The legacy system_settings row is inert.
  diagnostic_deposit_amount:            { label: 'Залог для диагностики (сом)',        description: 'Фиксированная сумма заморозки при подаче заявки на диагностику',          min: 0, max: 100000, step: 1000 },
  // Fail-closed on the server: until it is set, diagnostic requests are refused (no default
  // value has ever been approved, so none is pre-filled here).
  diagnostic_fee_amount:                { label: 'Стоимость диагностики (сом)',        description: 'Плата за диагностику, удерживаемая с продавца из залога. Пока не задана, заявки на диагностику не принимаются', min: 0, max: 100000, step: 100 },
  auction_confirm_hours:                { label: 'Подтверждение аукциона (ч)',         description: 'Часов на подтверждение аукциона администратором после создания',          min: 1, max: 168, step: 1 },
  completed_auction_visibility_hours:   { label: 'Видимость завершённых аукционов (ч)', description: 'Часов, в течение которых показываются завершённые аукционы',              min: 1, max: 8760, step: 24 },
};

function SettingsPage() {
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [loading, setLoading]   = useState(true);
  const [msg, setMsg]           = useState('');
  const [msgErr, setMsgErr]     = useState(false);
  const [errors, setErrors]     = useState<Record<string, string>>({});

  useEffect(() => {
    api.get('/admin/settings').then(r => setSettings(r.data)).finally(() => setLoading(false));
  }, []);

  // Only filled-in values are validated and sent: a setting that has never been configured is
  // shown as an empty field and simply stays unset until the admin enters a value.
  const filled = (val: string | undefined) => val != null && String(val).trim() !== '';

  const validate = () => {
    const errs: Record<string, string> = {};
    for (const [key, val] of Object.entries(settings)) {
      const meta = SETTINGS_META[key];
      if (!meta || !filled(val)) continue;
      const num = Number(val);
      if (isNaN(num) || num < meta.min) errs[key] = `Минимум: ${meta.min}`;
      if (num > meta.max) errs[key] = `Максимум: ${meta.max}`;
    }
    return errs;
  };

  const save = async () => {
    const errs = validate();
    if (Object.keys(errs).length > 0) { setErrors(errs); return; }
    setErrors({});
    const safe = Object.fromEntries(Object.entries(settings).filter(([k, v]) => k in SETTINGS_META && filled(v)));
    try {
      await api.put('/admin/settings', safe);
      setMsg('Настройки сохранены'); setMsgErr(false); setTimeout(() => setMsg(''), 3000);
    } catch (e: any) { setMsg(e.response?.data?.message || 'Ошибка'); setMsgErr(true); }
  };

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-xl font-bold text-gray-900">Настройки системы</h2>
        <p className="text-sm text-gray-500 mt-0.5">Глобальные параметры платформы</p>
      </div>

      <Alert msg={msg} err={msgErr} />

      {loading ? (
        <div className="p-8 text-center text-gray-400">Загрузка...</div>
      ) : (
        <div className="max-w-xl space-y-4">
          <div className="bg-white border border-gray-100 rounded-2xl shadow-sm divide-y divide-gray-50">
            {Object.keys(SETTINGS_META).map(key => {
              const meta = SETTINGS_META[key];
              return (
                <div key={key} className="p-5">
                  <label htmlFor={`setting-${key}`} className="text-sm font-semibold text-gray-800 block mb-0.5">
                    {meta.label}
                    {!filled(settings[key]) && (
                      <span className="ml-2 text-xs font-medium text-amber-700 bg-amber-50 px-2 py-0.5 rounded-full">Не задано</span>
                    )}
                  </label>
                  <p className="text-xs text-gray-400 mb-3">{meta.description}</p>
                  <div className="flex gap-3 items-center">
                    <input id={`setting-${key}`} type="number" value={settings[key] ?? ''} min={meta.min} max={meta.max} step={meta.step}
                      onChange={e => { setSettings({ ...settings, [key]: e.target.value }); setErrors(prev => ({ ...prev, [key]: '' })); }}
                      className={`w-40 border rounded-xl px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${errors[key] ? 'border-red-400' : 'border-gray-200'}`} />
                    <span className="text-xs text-gray-400">{meta.min}–{meta.max}</span>
                  </div>
                  {errors[key] && <p className="text-red-500 text-xs mt-1">{errors[key]}</p>}
                </div>
              );
            })}
          </div>
          <button onClick={save}
            className="bg-blue-600 text-white px-6 py-2.5 rounded-xl hover:bg-blue-700 font-medium transition-colors">
            Сохранить настройки
          </button>
        </div>
      )}
    </div>
  );
}

// ─── Main Layout ──────────────────────────────────────────────────────────────

export function AdminDashboard() {
  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      <AdminSidebar />
      <main className="flex-1 overflow-y-auto">
        <div className="p-6 max-w-6xl mx-auto">
          <Routes>
            <Route index element={<DashboardPage />} />
            <Route path="users" element={<UsersPage />} />
            <Route path="diagnostic/templates" element={<TemplatesPage />} />
            <Route path="diagnostic/reports" element={<ReportsPage />} />
            <Route path="auctions" element={<AuctionsPage />} />
            <Route path="sale/:auctionId" element={<AdminSalePage />} />
            <Route path="identity" element={<AdminIdentityPage />} />
            <Route path="reconciliation" element={<AdminReconciliationPage />} />
            <Route path="vehicles/needs-rediagnostic" element={<AdminVehiclesNeedsRediagnosticPage />} />
            <Route path="policy" element={<PolicyPage />} />
            <Route path="deposits" element={<DepositsPage />} />
            <Route path="payments/late-successes" element={<LateSuccessPaymentsPage />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="*" element={<Navigate to="/admin" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
