import { useEffect, useState } from 'react';
import axios from 'axios';

interface Stats {
  totalAuctions: number;
  activeAuctions: number;
  pendingReports: number;
  totalUsers: number;
}

export const AdminDashboard = () => {
  const [stats, setStats] = useState<Stats>({
    totalAuctions: 0,
    activeAuctions: 0,
    pendingReports: 0,
    totalUsers: 0
  });
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'dashboard' | 'auctions' | 'reports' | 'users'>('dashboard');

  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      window.location.href = '/login';
      return;
    }
    fetchStats(token);
  }, []);

  const fetchStats = async (token: string) => {
    try {
      // TODO: Заменить на реальные API
      setStats({
        totalAuctions: 12,
        activeAuctions: 5,
        pendingReports: 3,
        totalUsers: 45
      });
    } catch (error) {
      console.error('Error:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="text-center py-20">Загрузка...</div>;
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold mb-8">Админ панель</h1>
      
      {/* Вкладки */}
      <div className="border-b mb-6">
        <nav className="flex gap-4">
          <button
            onClick={() => setActiveTab('dashboard')}
            className={`px-4 py-2 ${activeTab === 'dashboard' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500'}`}
          >
            📊 Дашборд
          </button>
          <button
            onClick={() => setActiveTab('auctions')}
            className={`px-4 py-2 ${activeTab === 'auctions' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500'}`}
          >
            🏷️ Аукционы
          </button>
          <button
            onClick={() => setActiveTab('reports')}
            className={`px-4 py-2 ${activeTab === 'reports' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500'}`}
          >
            📋 Отчёты
          </button>
          <button
            onClick={() => setActiveTab('users')}
            className={`px-4 py-2 ${activeTab === 'users' ? 'border-b-2 border-blue-600 text-blue-600' : 'text-gray-500'}`}
          >
            👥 Пользователи
          </button>
        </nav>
      </div>
      
      {/* Дашборд */}
      {activeTab === 'dashboard' && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          <div className="bg-white rounded-lg shadow-md p-6">
            <div className="text-sm text-gray-500">Всего аукционов</div>
            <div className="text-3xl font-bold text-blue-600">{stats.totalAuctions}</div>
          </div>
          <div className="bg-white rounded-lg shadow-md p-6">
            <div className="text-sm text-gray-500">Активных аукционов</div>
            <div className="text-3xl font-bold text-green-600">{stats.activeAuctions}</div>
          </div>
          <div className="bg-white rounded-lg shadow-md p-6">
            <div className="text-sm text-gray-500">Отчётов на проверке</div>
            <div className="text-3xl font-bold text-orange-600">{stats.pendingReports}</div>
          </div>
          <div className="bg-white rounded-lg shadow-md p-6">
            <div className="text-sm text-gray-500">Пользователей</div>
            <div className="text-3xl font-bold text-purple-600">{stats.totalUsers}</div>
          </div>
        </div>
      )}
      
      {/* Аукционы */}
      {activeTab === 'auctions' && (
        <div className="bg-white rounded-lg shadow-md p-6">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-xl font-semibold">Управление аукционами</h2>
            <button className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700">
              + Создать аукцион
            </button>
          </div>
          <div className="text-gray-500 text-center py-10">
            Список аукционов появится здесь
          </div>
        </div>
      )}
      
      {/* Отчёты */}
      {activeTab === 'reports' && (
        <div className="bg-white rounded-lg shadow-md p-6">
          <h2 className="text-xl font-semibold mb-4">Отчёты на проверке</h2>
          <div className="text-gray-500 text-center py-10">
            Список отчётов появится здесь
          </div>
        </div>
      )}
      
      {/* Пользователи */}
      {activeTab === 'users' && (
        <div className="bg-white rounded-lg shadow-md p-6">
          <h2 className="text-xl font-semibold mb-4">Управление пользователями</h2>
          <div className="text-gray-500 text-center py-10">
            Список пользователей появится здесь
          </div>
        </div>
      )}
    </div>
  );
};