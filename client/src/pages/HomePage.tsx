import { useEffect, useState } from 'react';
import axios from 'axios';

interface Auction {
  id: number;
  vehicleInfo: string;
  currentPrice: number;
  status: string;
}

export const HomePage = () => {
  const [auctions, setAuctions] = useState<Auction[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    console.log('🔍 Token check:', token ? 'Exists' : 'Missing');
    
    if (!token) {
      console.log('🚫 No token, redirecting to login');
      window.location.href = '/login';
      return;
    }
    
    fetchAuctions(token);
  }, []);

  const fetchAuctions = async (token: string) => {
    try {
      console.log('📡 Fetching auctions...');
      const response = await axios.get('http://localhost:8080/api/admin/auctions?page=0&size=20', {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      
      console.log('✅ Auctions response:', response.data);
      setAuctions(response.data.content || []);
    } catch (error: any) {
      console.error('❌ Error fetching auctions:', error);
      if (error.response?.status === 401) {
        localStorage.removeItem('accessToken');
        window.location.href = '/login';
      }
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    window.location.href = '/login';
  };

  if (loading) {
    return <div className="text-center py-20">Загрузка...</div>;
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-8">
        <h1 className="text-3xl font-bold">Активные аукционы</h1>
        <button
          onClick={handleLogout}
          className="px-4 py-2 text-sm text-red-600 hover:text-red-800"
        >
          Выйти
        </button>
      </div>
      
      <div className="text-sm text-gray-500 mb-4">
        Найдено: {auctions.length} аукционов
      </div>
      
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {auctions.map((auction) => (
          <div key={auction.id} className="bg-white rounded-lg shadow-md p-4">
            <h3 className="font-semibold text-lg">
              {auction.vehicleInfo || `Лот №${auction.id}`}
            </h3>
            <p className="text-2xl font-bold text-blue-600 my-2">
              {auction.currentPrice.toLocaleString()} ₸
            </p>
            <p className="text-sm text-gray-500 mb-3">
              Статус: {auction.status === 'ACTIVE' ? '🟢 Идёт торг' : auction.status}
            </p>
            <a 
              href={`/auction/${auction.id}`}
              className="block w-full text-center bg-blue-600 text-white py-2 rounded-lg hover:bg-blue-700"
            >
              Подробнее
            </a>
          </div>
        ))}
      </div>
      
      {auctions.length === 0 && (
        <div className="text-center text-gray-500 py-20">
          Нет активных аукционов
        </div>
      )}
    </div>
  );
};