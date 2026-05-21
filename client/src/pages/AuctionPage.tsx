import { useParams } from 'react-router-dom';
import { useEffect, useState } from 'react';
import axios from 'axios';
import { useWebSocket } from '../hooks/useWebSocket';

interface AuctionDetail {
  id: number;
  vehicleInfo: string;
  currentPrice: number;
  startingPrice: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  minBidStep: number;
  status: string;
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
  const [wsConnected, setWsConnected] = useState(false);

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
      const response = await axios.get(`http://localhost:8080/api/admin/auctions/${id}`, {
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

  if (loading) {
    return <div className="text-center py-20">Загрузка...</div>;
  }

  if (!auction) {
    return <div className="text-center py-20 text-red-500">Аукцион не найден</div>;
  }

  const minNextBid = auction.currentPrice + auction.minBidStep;
  const isActive = auction.status === 'ACTIVE';

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
        
        <div className="text-4xl font-bold text-blue-600 mb-4">
          {auction.currentPrice.toLocaleString()} ₸
        </div>
        
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          <div>
            <div className="text-sm text-gray-500">Стартовая цена</div>
            <div className="font-medium">{auction.startingPrice.toLocaleString()} ₸</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Минимальный шаг</div>
            <div className="font-medium">{auction.minBidStep.toLocaleString()} ₸</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Всего ставок</div>
            <div className="font-medium">{auction.totalBids}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Статус</div>
            <div className="font-medium">
              {isActive ? '🟢 Идёт торг' : auction.status === 'CREATED' ? '⏳ Ожидает' : auction.status}
            </div>
          </div>
        </div>
        
        {auction.reservePrice && (
          <div className="mb-6 p-4 bg-gray-50 rounded-lg">
            <div className="text-sm text-gray-500 mb-1">Резервная цена (скрыта)</div>
            <div className="relative h-2 bg-gray-200 rounded-full overflow-hidden">
              <div 
                className="absolute left-0 top-0 h-full bg-blue-500 transition-all duration-500"
                style={{ width: `${Math.min((auction.currentPrice / auction.reservePrice) * 100, 100)}%` }}
              />
              <div 
                className="absolute top-0 w-0.5 h-4 bg-gray-700 -translate-y-1"
                style={{ left: '100%' }}
              />
            </div>
            <div className="text-xs text-gray-400 mt-2">
              🎯 При достижении этой отметки автомобиль будет обязательно продан
            </div>
          </div>
        )}
        
        {isActive && (
          <div className="border-t pt-6">
            <h3 className="text-lg font-semibold mb-4">Сделать ставку</h3>
            
            <div className="flex flex-col sm:flex-row gap-4">
              <div className="flex-1">
                <label className="block text-sm text-gray-600 mb-1">
                  Ваша ставка (мин. {minNextBid.toLocaleString()} ₸)
                </label>
                <input
                  type="number"
                  value={bidAmount}
                  onChange={(e) => setBidAmount(e.target.value)}
                  className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                />
              </div>
              <div className="flex items-end">
                <button
                  onClick={placeBid}
                  className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition"
                >
                  Сделать ставку
                </button>
              </div>
            </div>
            
            {auction.buyNowPrice && (
              <div className="mt-4">
                <button
                  onClick={() => {/* TODO: Buy Now */}}
                  className="w-full px-6 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 transition"
                >
                  Купить сейчас за {auction.buyNowPrice.toLocaleString()} ₸
                </button>
              </div>
            )}
          </div>
        )}
        
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