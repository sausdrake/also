import { useEffect, useState } from 'react';
import axios from 'axios';

interface Bid {
  id: number;
  amount: number;
  bidderName: string;
  createdAt: string;
}

interface BidHistoryProps {
  auctionId: number;
}

export const BidHistory = ({ auctionId }: BidHistoryProps) => {
  const [bids, setBids] = useState<Bid[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchBids();
  }, [auctionId]);

  const fetchBids = async () => {
    const token = localStorage.getItem('accessToken');
    try {
      // TODO: добавить эндпоинт для истории ставок на бэкенде
      // Пока заглушка
      setBids([]);
    } catch (error) {
      console.error('Error fetching bids:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="text-center py-4 text-gray-500">Загрузка истории...</div>;
  }

  return (
    <div className="mt-6">
      <h3 className="text-lg font-semibold mb-3">История ставок</h3>
      <div className="bg-gray-50 rounded-lg max-h-64 overflow-y-auto">
        {bids.length === 0 ? (
          <div className="text-center py-8 text-gray-500">
            Пока нет ставок
          </div>
        ) : (
          <table className="w-full">
            <thead className="sticky top-0 bg-gray-100">
              <tr>
                <th className="px-4 py-2 text-left text-sm">Дата</th>
                <th className="px-4 py-2 text-left text-sm">Участник</th>
                <th className="px-4 py-2 text-right text-sm">Сумма</th>
              </tr>
            </thead>
            <tbody>
              {bids.map((bid) => (
                <tr key={bid.id} className="border-t">
                  <td className="px-4 py-2 text-sm">{new Date(bid.createdAt).toLocaleTimeString()}</td>
                  <td className="px-4 py-2 text-sm">{bid.bidderName}</td>
                  <td className="px-4 py-2 text-right text-sm font-medium">{bid.amount.toLocaleString()} сом</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};