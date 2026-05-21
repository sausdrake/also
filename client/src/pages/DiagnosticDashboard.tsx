import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';

interface Vehicle {
  id: number;
  brand: string;
  model: string;
  year: number;
  vin: string;
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'NEEDS_FIXES';
  createdAt: string;
}

export const DiagnosticDashboard = () => {
  const [vehicles, setVehicles] = useState<Vehicle[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateForm, setShowCreateForm] = useState(false);
  const navigate = useNavigate();

  // Форма создания автомобиля
  const [form, setForm] = useState({
    brand: '',
    model: '',
    year: new Date().getFullYear(),
    vin: '',
    type: 'SEDAN',
    licensePlate: '',
    color: '',
    mileage: '',
    engineCapacity: '',
    fuelType: 'PETROL',
    transmission: '',
    description: ''
  });

  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) {
      navigate('/login');
      return;
    }
    fetchVehicles(token);
  }, []);

  const fetchVehicles = async (token: string) => {
    try {
      const response = await axios.get('http://localhost:8080/api/diagnostic/vehicles/my', {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setVehicles(response.data);
    } catch (error) {
      console.error('Error:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const token = localStorage.getItem('accessToken');
    
    try {
      await axios.post('http://localhost:8080/api/diagnostic/vehicles', form, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setShowCreateForm(false);
      setForm({
        brand: '', model: '', year: new Date().getFullYear(), vin: '',
        type: 'SEDAN', licensePlate: '', color: '', mileage: '',
        engineCapacity: '', fuelType: 'PETROL', transmission: '', description: ''
      });
      fetchVehicles(token!);
    } catch (error) {
      console.error('Error creating vehicle:', error);
    }
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'DRAFT': return <span className="bg-gray-200 text-gray-700 px-2 py-1 rounded text-xs">Черновик</span>;
      case 'PENDING_REVIEW': return <span className="bg-yellow-100 text-yellow-700 px-2 py-1 rounded text-xs">На проверке</span>;
      case 'APPROVED': return <span className="bg-green-100 text-green-700 px-2 py-1 rounded text-xs">Одобрен</span>;
      case 'NEEDS_FIXES': return <span className="bg-red-100 text-red-700 px-2 py-1 rounded text-xs">Требует доработки</span>;
      default: return <span>{status}</span>;
    }
  };

  if (loading) {
    return <div className="text-center py-20">Загрузка...</div>;
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-8">
        <h1 className="text-3xl font-bold">Мои отчёты</h1>
        <button
          onClick={() => setShowCreateForm(true)}
          className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700"
        >
          + Создать отчёт
        </button>
      </div>

      {/* Форма создания */}
      {showCreateForm && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-2xl w-full max-h-[90vh] overflow-y-auto">
            <h2 className="text-2xl font-bold mb-4">Новый отчёт</h2>
            <form onSubmit={handleSubmit}>
              <div className="grid grid-cols-2 gap-4">
                <input
                  type="text"
                  placeholder="Марка *"
                  required
                  value={form.brand}
                  onChange={(e) => setForm({...form, brand: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
                <input
                  type="text"
                  placeholder="Модель *"
                  required
                  value={form.model}
                  onChange={(e) => setForm({...form, model: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
                <input
                  type="number"
                  placeholder="Год *"
                  required
                  value={form.year}
                  onChange={(e) => setForm({...form, year: parseInt(e.target.value)})}
                  className="border rounded-lg px-3 py-2"
                />
                <input
                  type="text"
                  placeholder="VIN *"
                  required
                  value={form.vin}
                  onChange={(e) => setForm({...form, vin: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
                <select
                  value={form.type}
                  onChange={(e) => setForm({...form, type: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                >
                  <option value="SEDAN">Седан</option>
                  <option value="SUV">Внедорожник</option>
                  <option value="HATCHBACK">Хэтчбек</option>
                  <option value="COUPE">Купе</option>
                  <option value="WAGON">Универсал</option>
                </select>
                <input
                  type="text"
                  placeholder="Госномер"
                  value={form.licensePlate}
                  onChange={(e) => setForm({...form, licensePlate: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
                <input
                  type="text"
                  placeholder="Цвет"
                  value={form.color}
                  onChange={(e) => setForm({...form, color: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
                <input
                  type="number"
                  placeholder="Пробег"
                  value={form.mileage}
                  onChange={(e) => setForm({...form, mileage: e.target.value})}
                  className="border rounded-lg px-3 py-2"
                />
              </div>
              
              <div className="flex justify-end gap-2 mt-4">
                <button
                  type="button"
                  onClick={() => setShowCreateForm(false)}
                  className="px-4 py-2 border rounded-lg hover:bg-gray-50"
                >
                  Отмена
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
                >
                  Создать
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Список отчётов */}
      <div className="bg-white rounded-lg shadow-md overflow-hidden">
        <table className="w-full">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">ID</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Автомобиль</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">VIN</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Статус</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Действия</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {vehicles.map((vehicle) => (
              <tr key={vehicle.id}>
                <td className="px-6 py-4 text-sm">{vehicle.id}</td>
                <td className="px-6 py-4 text-sm">{vehicle.brand} {vehicle.model} ({vehicle.year})</td>
                <td className="px-6 py-4 text-sm font-mono">{vehicle.vin}</td>
                <td className="px-6 py-4 text-sm">{getStatusBadge(vehicle.status)}</td>
                <td className="px-6 py-4 text-sm">
                  <button className="text-blue-600 hover:text-blue-800 mr-3">Редактировать</button>
                  {vehicle.status === 'DRAFT' && (
                    <button className="text-green-600 hover:text-green-800">Отправить</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {vehicles.length === 0 && (
          <div className="text-center text-gray-500 py-10">
            Нет созданных отчётов
          </div>
        )}
      </div>
    </div>
  );
};