import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuthStore } from '../store/authStore';
import { formatDateTime } from '../utils/formatDate';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

interface Auction {
  id: number;
  vehicleInfo: string;
  currentPrice: number;
  startingPrice: number;
  status: string;
  endTime: string;
  startTime: string;
}

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  ACTIVE:    { label: '🟢 Идёт торг',        color: 'text-green-600' },
  CREATED:   { label: '⏳ Скоро начнётся',    color: 'text-blue-600' },
  COMPLETED: { label: '🏁 Завершён',          color: 'text-gray-500' },
  EXPIRED:   { label: '⌛ Истёк',             color: 'text-gray-400' },
  CANCELLED: { label: '❌ Отменён',           color: 'text-red-500' },
};

type FilterTab = 'ALL' | 'ACTIVE' | 'CREATED' | 'COMPLETED';

export const HomePage = () => {
  const [auctions, setAuctions] = useState<Auction[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterTab>('ALL');
  const { isAuthenticated } = useAuthStore();
  const navigate = useNavigate();

  useEffect(() => {
    fetchAuctions();
  }, []);

  const fetchAuctions = async () => {
    try {
      const token = localStorage.getItem('accessToken');
      const headers = token ? { Authorization: `Bearer ${token}` } : {};
      const { data } = await axios.get(
        `${BASE_URL}/api/public/auctions?page=0&size=50`,
        { headers }
      );
      setAuctions(data.content || []);
    } catch {
      // public endpoint — don't redirect on error
    } finally {
      setLoading(false);
    }
  };

  const filtered = auctions.filter(a => {
    if (filter === 'ALL') return a.status === 'ACTIVE' || a.status === 'CREATED';
    return a.status === filter;
  });

  const TABS: { key: FilterTab; label: string }[] = [
    { key: 'ALL', label: 'Все активные' },
    { key: 'ACTIVE', label: 'Идут торги' },
    { key: 'CREATED', label: 'Скоро' },
  ];

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24">
        <div className="animate-spin text-3xl">⏳</div>
      </div>
    );
  }

  return (
    <div className="max-w-7xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Аукционы автомобилей</h1>
        <p className="text-gray-500 text-sm mt-1">Покупайте автомобили с проверенной диагностикой</p>
      </div>

      {/* Фильтры */}
      <div className="flex gap-2 mb-6 flex-wrap">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            onClick={() => setFilter(key)}
            className={`px-4 py-2 rounded-full text-sm font-medium transition-colors ${
              filter === key
                ? 'bg-blue-600 text-white'
                : 'bg-white border text-gray-600 hover:bg-gray-50'
            }`}
          >
            {label}
          </button>
        ))}
        <span className="ml-auto text-sm text-gray-400 self-center">
          Найдено: {filtered.length}
        </span>
      </div>

      {/* Список */}
      {filtered.length === 0 ? (
        <div className="text-center text-gray-400 py-20 bg-white rounded-xl border">
          Нет аукционов в этой категории
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
          {filtered.map((auction) => {
            const statusInfo = STATUS_LABELS[auction.status] ?? { label: auction.status, color: 'text-gray-500' };
            const endDate = auction.endTime ? new Date(auction.endTime) : null;
            return (
              <div
                key={auction.id}
                className="bg-white rounded-xl border shadow-sm hover:shadow-md transition-shadow overflow-hidden"
              >
                {/* Placeholder photo area */}
                <div className="h-36 bg-gradient-to-br from-blue-50 to-gray-100 flex items-center justify-center text-4xl">
                  🚗
                </div>
                <div className="p-4">
                  <h3 className="font-semibold text-gray-900 truncate">
                    {auction.vehicleInfo || `Лот №${auction.id}`}
                  </h3>
                  <div className={`text-xs mt-1 ${statusInfo.color}`}>{statusInfo.label}</div>
                  <div className="mt-3">
                    <span className="text-xs text-gray-400">Текущая цена</span>
                    <div className="text-2xl font-bold text-blue-600">
                      {Number(auction.currentPrice).toLocaleString('ru')} сом
                    </div>
                  </div>
                  {auction.endTime && auction.status === 'ACTIVE' && (
                    <div className="text-xs text-gray-400 mt-1">
                      До конца: {formatDateTime(auction.endTime)}
                    </div>
                  )}
                  <button
                    onClick={() => navigate(`/auction/${auction.id}`)}
                    className="mt-4 w-full bg-blue-600 text-white py-2 rounded-lg hover:bg-blue-700 text-sm font-medium"
                  >
                    {isAuthenticated ? 'Участвовать' : 'Подробнее'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
