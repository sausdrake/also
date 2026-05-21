import { useState } from 'react';
import axios from 'axios';

export const LoginPage = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      console.log('🔐 Login attempt for:', username);
      
      const response = await axios.post('http://localhost:8080/api/auth/login', {
        username,
        password
      });
      
      console.log('✅ Login response:', response.data);
      
      const token = response.data.accessToken;
      
      if (!token) {
        throw new Error('No token received');
      }
      
      // Сохраняем токен
      localStorage.setItem('accessToken', token);
      console.log('💾 Token saved:', token.substring(0, 50) + '...');
      
      // Проверяем что сохранилось
      const savedToken = localStorage.getItem('accessToken');
      console.log('🔍 Verifying saved token:', savedToken ? 'Yes' : 'No');
      
      // Переходим на главную
      window.location.href = '/';
      
    } catch (err: any) {
      console.error('❌ Login error:', err);
      setError(err.response?.data?.message || 'Ошибка входа');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md w-full space-y-8">
        <div>
          <h2 className="mt-6 text-center text-3xl font-extrabold text-gray-900">
            Вход в AutoAuction
          </h2>
        </div>
        
        <form className="mt-8 space-y-6" onSubmit={handleSubmit}>
          <div className="rounded-md shadow-sm -space-y-px">
            <div>
              <input
                type="text"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-t-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Имя пользователя"
              />
            </div>
            <div>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="appearance-none rounded-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 rounded-b-md focus:outline-none focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm"
                placeholder="Пароль"
              />
            </div>
          </div>

          {error && (
            <div className="text-red-500 text-sm text-center">{error}</div>
          )}

          <div>
            <button
              type="submit"
              disabled={loading}
              className="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              {loading ? 'Вход...' : 'Войти'}
            </button>
          </div>
          
          <div className="text-sm text-center text-gray-500">
            <p>Тестовые аккаунты:</p>
            <p>admin / admin123</p>
            <p>user / user123</p>
          </div>
        </form>
      </div>
    </div>
  );
};