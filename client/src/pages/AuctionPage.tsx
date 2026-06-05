import { useParams, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import axios from 'axios';
import { useWebSocket } from '../hooks/useWebSocket';
import { AuctionTimer } from '../components/AuctionTimer';
import { useAuthStore } from '../store/authStore';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

interface AuctionDetail {
  id: number;
  vehicleInfo: string;
  currentPrice: number;
  startingPrice: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  minBidStep: number;
  status: string;
  startTime: string;
  endTime: string;
  totalBids: number;
}

const MAX_BID = 999_999_999;

export const AuctionPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const auctionId = parseInt(id || '0');
  const { isAuthenticated, accessToken } = useAuthStore();
  const { isConnected, lastMessage } = useWebSocket(auctionId);
  const [auction, setAuction] = useState<AuctionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [bidAmount, setBidAmount] = useState('');
  const [bidError, setBidError] = useState('');
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  useEffect(() => {
    if (lastMessage && auction) {
      setAuction(prev => prev ? {
        ...prev,
        currentPrice: lastMessage.currentPrice,
        totalBids: lastMessage.totalBids,
        status: lastMessage.status,
      } : null);
      const minBid = lastMessage.currentPrice + (auction?.minBidStep ?? 0);
      setBidAmount(String(minBid));
      showMsg(`💰 Новая ставка! Цена: ${lastMessage.currentPrice.toLocaleString('ru')} сом`, 'success');
    }
  }, [lastMessage]);

  useEffect(() => {
    fetchAuction();
  }, [id]);

  const authHeaders = () => {
    const token = accessToken || localStorage.getItem('accessToken');
    return token ? { Authorization: `Bearer ${token}` } : {};
  };

  const showMsg = (text: string, type: 'success' | 'error') => {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 4000);
  };

  const fetchAuction = async () => {
    try {
      const { data } = await axios.get(
        `${BASE_URL}/api/public/auctions/${id}`,
        { headers: authHeaders() }
      );
      setAuction(data);
      setBidAmount(String(data.currentPrice + data.minBidStep));
    } catch {
      // handled below via null check
    } finally {
      setLoading(false);
    }
  };

  const validateBid = (raw: string): string | null => {
    const val = Number(raw);
    if (!raw.trim() || isNaN(val)) return 'Введите сумму ставки';
    if (!Number.isFinite(val)) return 'Некорректная сумма';
    if (val <= 0) return 'Сумма ставки должна быть больше 0';
    if (val > MAX_BID) return `Сумма не может превышать ${MAX_BID.toLocaleString('ru')} сом`;
    if (auction && val < auction.currentPrice + auction.minBidStep) {
      return `Минимальная ставка: ${(auction.currentPrice + auction.minBidStep).toLocaleString('ru')} сом`;
    }
    return null;
  };

  const placeBid = async () => {
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }
    const err = validateBid(bidAmount);
    if (err) { setBidError(err); return; }
    setBidError('');

    try {
      await axios.post(
        `${BASE_URL}/api/auctions/${id}/bids`,
        { amount: Number(bidAmount) },
        { headers: authHeaders() }
      );
      showMsg(`✅ Ставка ${Number(bidAmount).toLocaleString('ru')} сом принята!`, 'success');
    } catch (e: any) {
      showMsg(e.response?.data?.message || 'Ошибка при ставке', 'error');
    }
  };

  const buyNow = async () => {
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }
    if (!auction?.buyNowPrice) {
      showMsg('Buy Now не доступен', 'error');
      return;
    }
    try {
      await axios.post(
        `${BASE_URL}/api/auctions/${id}/buy-now`,
        {},
        { headers: authHeaders() }
      );
      showMsg(`🎉 Поздравляем! Куплено за ${auction.buyNowPrice.toLocaleString('ru')} сом`, 'success');
      setAuction(prev => prev ? { ...prev, status: 'COMPLETED' } : null);
    } catch (e: any) {
      showMsg(e.response?.data?.message || 'Ошибка при покупке', 'error');
    }
  };

  if (loading) return <div className="flex justify-center py-20 text-2xl">⏳</div>;

  if (!auction) return (
    <div className="text-center py-20">
      <p className="text-red-500 text-lg">Аукцион не найден</p>
      <button onClick={() => navigate('/')} className="mt-4 text-blue-600 hover:underline">← На главную</button>
    </div>
  );

  const minNextBid = auction.currentPrice + auction.minBidStep;
  const isActive = auction.status === 'ACTIVE';
  const isCreated = auction.status === 'CREATED';
  const isCompleted = ['COMPLETED', 'SOLD', 'EXPIRED', 'CANCELLED'].includes(auction.status);
  const reserveProgress = auction.reservePrice
    ? Math.min((auction.currentPrice / auction.reservePrice) * 100, 100)
    : 0;

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <button onClick={() => navigate('/')} className="text-blue-600 hover:underline mb-4 inline-block text-sm">
        ← Все аукционы
      </button>

      {/* WS-индикатор */}
      <div className="mb-4 text-right">
        <span className={`text-xs px-2 py-1 rounded-full ${isConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
          {isConnected ? '🟢 Live' : '🔴 Ожидание'}
        </span>
      </div>

      <div className="bg-white rounded-xl shadow-sm border p-6 space-y-6">
        <h1 className="text-2xl font-bold">{auction.vehicleInfo || `Аукцион №${auction.id}`}</h1>

        <AuctionTimer startTime={auction.startTime} endTime={auction.endTime} status={auction.status} />

        {/* Цена */}
        <div className="text-center">
          <div className="text-xs text-gray-400">Текущая цена</div>
          <div className="text-5xl font-bold text-blue-600 mt-1">
            {Number(auction.currentPrice).toLocaleString('ru')} сом
          </div>
          <div className="text-xs text-gray-400 mt-1">Ставок: {auction.totalBids}</div>
        </div>

        {/* Параметры */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 p-4 bg-gray-50 rounded-xl text-sm">
          {[
            ['Старт', `${Number(auction.startingPrice).toLocaleString('ru')} сом`],
            ['Шаг', `${Number(auction.minBidStep).toLocaleString('ru')} сом`],
            ['Лот', `#${auction.id}`],
            ['Статус', auction.status],
          ].map(([k, v]) => (
            <div key={k}>
              <div className="text-xs text-gray-400">{k}</div>
              <div className="font-medium">{v}</div>
            </div>
          ))}
        </div>

        {/* Резервная цена */}
        {auction.reservePrice && (
          <div className="p-4 bg-gray-50 rounded-xl">
            <div className="flex justify-between text-xs text-gray-500 mb-1">
              <span>Прогресс к резервной цене</span>
              <span>{Math.round(reserveProgress)}%</span>
            </div>
            <div className="h-2.5 bg-gray-200 rounded-full overflow-hidden">
              <div
                className="h-full bg-blue-500 rounded-full transition-all duration-500"
                style={{ width: `${reserveProgress}%` }}
              />
            </div>
            <div className="text-xs text-gray-400 mt-1.5">
              {reserveProgress >= 100
                ? '✅ Резервная цена достигнута — автомобиль будет продан'
                : '🎯 При достижении отметки продажа гарантирована'}
            </div>
          </div>
        )}

        {/* Buy Now */}
        {auction.buyNowPrice && isCreated && (
          <div>
            <button
              onClick={buyNow}
              className="w-full py-3 bg-green-600 text-white rounded-xl hover:bg-green-700 font-semibold text-lg"
            >
              💎 Купить сейчас за {Number(auction.buyNowPrice).toLocaleString('ru')} сом
            </button>
            <p className="text-xs text-gray-400 text-center mt-1">
              До начала аукциона — по фиксированной цене
            </p>
          </div>
        )}

        {/* Форма ставки */}
        {isActive && (
          <div className="border-t pt-6">
            <h3 className="font-semibold mb-3">Сделать ставку</h3>
            {!isAuthenticated && (
              <div className="mb-3 p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
                <button onClick={() => navigate('/login')} className="text-blue-600 font-medium hover:underline">Войдите</button>, чтобы участвовать в торгах
              </div>
            )}
            <div className="flex flex-col sm:flex-row gap-3">
              <div className="flex-1">
                <label className="text-xs text-gray-500 mb-1 block">
                  Мин. ставка: {minNextBid.toLocaleString('ru')} сом
                </label>
                <input
                  type="number"
                  value={bidAmount}
                  min={minNextBid}
                  max={MAX_BID}
                  onChange={e => { setBidAmount(e.target.value); setBidError(''); }}
                  className={`w-full px-4 py-3 border rounded-xl text-lg focus:ring-2 focus:ring-blue-500 focus:outline-none ${bidError ? 'border-red-400' : 'border-gray-300'}`}
                  placeholder="Введите сумму"
                  disabled={!isAuthenticated}
                />
                {bidError && <p className="text-red-500 text-xs mt-1">{bidError}</p>}
              </div>
              <div className="flex items-end">
                <button
                  onClick={placeBid}
                  disabled={!isAuthenticated}
                  className="px-8 py-3 bg-blue-600 text-white rounded-xl hover:bg-blue-700 font-semibold text-lg disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  Ставка
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Завершён */}
        {isCompleted && (
          <div className="border-t pt-6 text-center">
            <div className="text-lg font-semibold text-gray-600">🏁 Аукцион завершён</div>
          </div>
        )}

        {/* Flash-сообщение */}
        {message && (
          <div className={`p-3 rounded-xl text-sm font-medium ${
            message.type === 'success' ? 'bg-green-50 text-green-700 border border-green-200' : 'bg-red-50 text-red-700 border border-red-200'
          }`}>
            {message.text}
          </div>
        )}
      </div>
    </div>
  );
};
