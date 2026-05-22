import { useParams } from 'react-router-dom';
import { useEffect, useState } from 'react';
import axios from 'axios';
import { useWebSocket } from '../hooks/useWebSocket';
import { AuctionTimer } from '../components/AuctionTimer';

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

export const AuctionPage = () => {
  const { id } = useParams();
  const auctionId = parseInt(id || '0');
  const { isConnected, lastMessage } = useWebSocket(auctionId);
  const [auction, setAuction] = useState<AuctionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [bidAmount, setBidAmount] = useState('');
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

  // Обновляем данные из WebSocket
  useEffect(() => {
    if (lastMessage && auction) {
      console.log('🔄 Updating price via WebSocket:', lastMessage.currentPrice);
      setAuction(prev => prev ? {
        ...prev,
        currentPrice: lastMessage.currentPrice,
        totalBids: lastMessage.totalBids,
        status: lastMessage.status,
      } : null);
      
      // Обновляем минимальную ставку
      if (auction) {
        const minBid = lastMessage.currentPrice + auction.minBidStep;
        setBidAmount(minBid.toString());
      }
      
      // Показываем уведомление о новой ставке
      setMessage({ text: `💰 Новая ставка! Цена: ${lastMessage.currentPrice.toLocaleString()} ₸`, type: 'success' });
      setTimeout(() => setMessage(null), 3000);
    }
  }, [lastMessage]);

  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      window.location.href = '/login';
      return;
    }
    fetchAuction(token);
  }, [id]);

  const fetchAuction = async (token: string) => {
    try {
      const response = await axios.get(`http://localhost:8080/api/public/auctions/${id}`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setAuction(response.data);
      const minBid = response.data.currentPrice + response.data.minBidStep;
      setBidAmount(minBid.toString());
    } catch (error) {
      console.error('Error:', error);
    } finally {
      setLoading(false);
    }
  };

  const placeBid = async () => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      window.location.href = '/login';
      return;
    }

    const amount = parseInt(bidAmount);
    if (isNaN(amount)) {
      setMessage({ text: 'Введите сумму ставки', type: 'error' });
      return;
    }

    try {
      const response = await axios.post(
        `http://localhost:8080/api/auctions/${id}/bids`,
        { amount },
        { headers: { 'Authorization': `Bearer ${token}` } }
      );
      
      setMessage({ text: `✅ Ставка ${amount.toLocaleString()} ₸ принята!`, type: 'success' });
      setTimeout(() => setMessage(null), 3000);
      
    } catch (error: any) {
      setMessage({ text: error.response?.data?.message || 'Ошибка при ставке', type: 'error' });
      setTimeout(() => setMessage(null), 3000);
    }
  };

  const buyNow = async () => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      window.location.href = '/login';
      return;
    }

    if (!auction?.buyNowPrice) {
      setMessage({ text: 'Buy Now не доступен для этого аукциона', type: 'error' });
      return;
    }

    try {
      const response = await axios.post(
        `http://localhost:8080/api/auctions/${id}/buy-now`,
        {},
        { headers: { 'Authorization': `Bearer ${token}` } }
      );
      
      setMessage({ text: `🎉 Поздравляем! Вы купили автомобиль за ${auction.buyNowPrice.toLocaleString()} ₸`, type: 'success' });
      
      // Обновляем статус аукциона
      setAuction(prev => prev ? { ...prev, status: 'COMPLETED' } : null);
      
      setTimeout(() => setMessage(null), 5000);
    } catch (error: any) {
      setMessage({ text: error.response?.data?.message || 'Ошибка при покупке', type: 'error' });
      setTimeout(() => setMessage(null), 3000);
    }
  };

  if (loading) {
    return <div className="text-center py-20">Загрузка...</div>;
  }

  if (!auction) {
    return <div className="text-center py-20 text-red-500">Аукцион не найден</div>;
  }

  const minNextBid = auction.currentPrice + auction.minBidStep;
  const isActive = auction.status === 'ACTIVE';
  const isCreated = auction.status === 'CREATED';
  const isCompleted = auction.status === 'COMPLETED';

  // Прогресс резервной цены
  const reserveProgress = auction.reservePrice 
    ? Math.min((auction.currentPrice / auction.reservePrice) * 100, 100)
    : 0;

  return (
    <div className="container mx-auto px-4 py-8">
      <a href="/" className="text-blue-600 hover:underline mb-4 inline-block">
        ← Назад к списку
      </a>
      
      {/* WebSocket статус */}
      <div className="mb-4 text-right">
        <span className={`text-xs px-2 py-1 rounded ${isConnected ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
          {isConnected ? '🟢 Live обновления' : '🔴 Ожидание подключения'}
        </span>
      </div>
      
      <div className="bg-white rounded-lg shadow-md p-6">
        <h1 className="text-2xl font-bold mb-4">
          {auction.vehicleInfo || `Аукцион №${auction.id}`}
        </h1>
        
        {/* Таймер */}
        <div className="mb-6">
          <AuctionTimer 
            startTime={auction.startTime} 
            endTime={auction.endTime} 
            status={auction.status} 
          />
        </div>
        
        {/* Текущая цена */}
        <div className="text-center mb-6">
          <div className="text-sm text-gray-500">Текущая цена</div>
          <div className="text-5xl font-bold text-blue-600">
            {auction.currentPrice.toLocaleString()} ₸
          </div>
          <div className="text-sm text-gray-400 mt-1">
            Всего ставок: {auction.totalBids}
          </div>
        </div>
        
        {/* Характеристики */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6 p-4 bg-gray-50 rounded-lg">
          <div>
            <div className="text-sm text-gray-500">Стартовая цена</div>
            <div className="font-medium">{auction.startingPrice.toLocaleString()} ₸</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Минимальный шаг</div>
            <div className="font-medium">{auction.minBidStep.toLocaleString()} ₸</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Статус</div>
            <div className="font-medium">
              {isActive && '🟢 Идёт торг'}
              {isCreated && '⏳ Ожидает начала'}
              {isCompleted && '🏁 Завершён'}
            </div>
          </div>
          <div>
            <div className="text-sm text-gray-500">ID лота</div>
            <div className="font-medium">#{auction.id}</div>
          </div>
        </div>
        
        {/* Шкала резервной цены */}
        {auction.reservePrice && (
          <div className="mb-6 p-4 bg-gray-50 rounded-lg">
            <div className="flex justify-between text-sm text-gray-500 mb-1">
              <span>Прогресс к резервной цене</span>
              <span>{Math.round(reserveProgress)}%</span>
            </div>
            <div className="relative h-3 bg-gray-200 rounded-full overflow-hidden">
              <div 
                className="absolute left-0 top-0 h-full bg-blue-500 transition-all duration-500"
                style={{ width: `${reserveProgress}%` }}
              />
              <div 
                className="absolute top-0 w-0.5 h-5 bg-gray-700 -translate-y-1"
                style={{ left: '100%' }}
              >
                <div className="absolute -top-5 left-1/2 -translate-x-1/2 whitespace-nowrap text-xs text-gray-500">
                  🎯 Резерв
                </div>
              </div>
            </div>
            <div className="text-xs text-gray-400 mt-2">
              {reserveProgress >= 100 
                ? '✅ Резервная цена достигнута! Автомобиль будет продан.'
                : '🎯 При достижении этой отметки автомобиль будет обязательно продан'}
            </div>
          </div>
        )}
        
        {/* Buy Now (только для CREATED статуса) */}
        {auction.buyNowPrice && isCreated && (
          <div className="mb-6">
            <button
              onClick={buyNow}
              className="w-full px-6 py-3 bg-green-600 text-white rounded-lg hover:bg-green-700 transition font-semibold text-lg"
            >
              💎 Купить сейчас за {auction.buyNowPrice.toLocaleString()} ₸
            </button>
            <p className="text-xs text-gray-400 text-center mt-2">
              До начала аукциона автомобиль можно купить по фиксированной цене
            </p>
          </div>
        )}
        
        {/* Форма ставки (только для ACTIVE статуса) */}
        {isActive && (
          <div className="border-t pt-6">
            <h3 className="text-lg font-semibold mb-4">Сделать ставку</h3>
            
            <div className="flex flex-col sm:flex-row gap-4">
              <div className="flex-1">
                <label className="block text-sm text-gray-600 mb-1">
                  Минимальная следующая ставка: {minNextBid.toLocaleString()} ₸
                </label>
                <input
                  type="number"
                  value={bidAmount}
                  onChange={(e) => setBidAmount(e.target.value)}
                  className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 text-lg"
                  placeholder="Введите сумму ставки"
                />
              </div>
              <div className="flex items-end">
                <button
                  onClick={placeBid}
                  className="px-8 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition font-semibold text-lg"
                >
                  Сделать ставку
                </button>
              </div>
            </div>
          </div>
        )}
        
        {/* Сообщение о завершении */}
        {isCompleted && (
          <div className="border-t pt-6 text-center">
            <div className="text-lg text-green-600 font-semibold">
              🏆 Аукцион завершён
            </div>
            <div className="text-sm text-gray-500 mt-2">
              Спасибо за участие!
            </div>
          </div>
        )}
        
        {/* Уведомления */}
        {message && (
          <div className={`mt-4 p-3 rounded-lg ${
            message.type === 'success' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
          }`}>
            {message.text}
          </div>
        )}
      </div>
    </div>
  );
};