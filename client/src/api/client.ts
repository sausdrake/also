import axios from 'axios';
import { useAuthStore } from '../store/authStore';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

const api = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let isRefreshing = false;
let failedQueue: { resolve: (v: string) => void; reject: (e: unknown) => void }[] = [];

const processQueue = (error: unknown, token: string | null) => {
  failedQueue.forEach((p) => (error ? p.reject(error) : p.resolve(token!)));
  failedQueue = [];
};

function forceLogout() {
  const store = useAuthStore.getState();
  store.logout().catch(() => {});
  window.history.replaceState(null, '', '/login');
  window.dispatchEvent(new PopStateEvent('popstate'));
}

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;

    if (error.response?.status === 401 && !original._retry) {
      const { isAuthenticated } = useAuthStore.getState();
      if (!isAuthenticated) return Promise.reject(error);

      // Если токен в запросе отличается от текущего localStorage — другой поток
      // уже выполнил refresh. Просто повторяем запрос с актуальным токеном.
      const requestToken = (original.headers?.Authorization || '').replace('Bearer ', '');
      const currentToken = localStorage.getItem('accessToken');
      if (requestToken && currentToken && requestToken !== currentToken) {
        original._retry = true;
        original.headers.Authorization = `Bearer ${currentToken}`;
        return api(original);
      }

      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          original._retry = true;
          original.headers.Authorization = `Bearer ${token}`;
          return api(original);
        }).catch((e) => Promise.reject(e));
      }

      original._retry = true;
      isRefreshing = true;
      const refreshToken = localStorage.getItem('refreshToken');

      if (!refreshToken) {
        isRefreshing = false;
        processQueue(error, null);
        forceLogout();
        return Promise.reject(error);
      }

      try {
        const { data } = await axios.post(`${BASE_URL}/api/auth/refresh`, { refreshToken });
        const newAccessToken: string = data.accessToken;
        const newRefreshToken: string = data.refreshToken;

        localStorage.setItem('accessToken', newAccessToken);
        if (newRefreshToken) localStorage.setItem('refreshToken', newRefreshToken);

        const { user } = useAuthStore.getState();
        if (user) {
          useAuthStore.setState({ accessToken: newAccessToken, isAuthenticated: true });
        }

        processQueue(null, newAccessToken);
        original.headers.Authorization = `Bearer ${newAccessToken}`;
        return api(original);
      } catch (err) {
        processQueue(err, null);
        forceLogout();
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

export default api;
