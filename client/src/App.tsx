import { lazy } from 'react';
import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { Header } from './components/Header';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { TelegramCallbackPage } from './pages/TelegramCallbackPage';
import { ForbiddenPage } from './pages/ForbiddenPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProtectedRoute } from './components/ProtectedRoute';
import { RouteBoundary } from './components/RouteBoundary';

// FE-04: the five feature areas load on first visit, each in its own chunk. The landing page, the
// sign-in pages and the error pages stay in the initial bundle - they must work even when a chunk
// cannot be fetched. Guards stay in front of the lazy pages: an unauthorized visit redirects
// before the chunk is requested.
const AuctionPage = lazy(() => import('./pages/AuctionPage').then(m => ({ default: m.AuctionPage })));
const ProfilePage = lazy(() => import('./pages/ProfilePage').then(m => ({ default: m.ProfilePage })));
const ChangePasswordPage = lazy(() => import('./pages/ChangePasswordPage').then(m => ({ default: m.ChangePasswordPage })));
const AdminDashboard = lazy(() => import('./pages/AdminDashboard').then(m => ({ default: m.AdminDashboard })));
const DiagnosticDashboard = lazy(() => import('./pages/DiagnosticDashboard').then(m => ({ default: m.DiagnosticDashboard })));

function AppContent() {
  const location = useLocation();
  const isAdminPage = location.pathname.startsWith('/admin');
  const isDiagnosticPage = location.pathname.startsWith('/diagnostic');
  const hideHeader = isAdminPage || isDiagnosticPage;
  // FE-04: a failed chunk load is cleared when the user goes to another area - keyed by the first
  // path segment only, so moving between tabs inside /admin or /profile never remounts the page.
  const area = '/' + location.pathname.split('/')[1];

  return (
    <div className={`min-h-screen bg-gray-50 ${hideHeader ? '' : 'flex flex-col'}`}>
      {!hideHeader && <Header />}

      <main className={hideHeader ? '' : 'flex-1'}>
        <RouteBoundary key={area}>
        <Routes>
          {/* Публичные маршруты */}
          <Route path="/login"    element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/auth/telegram/callback" element={<TelegramCallbackPage />} />
          <Route path="/forbidden" element={<ForbiddenPage />} />

          {/* Публичная лента */}
          <Route path="/" element={<HomePage />} />
          <Route path="/auction/:id" element={<AuctionPage />} />

          {/* Защищённые маршруты */}
          <Route path="/change-password" element={
            <ProtectedRoute>
              <ChangePasswordPage />
            </ProtectedRoute>
          } />

          <Route path="/profile/*" element={
            <ProtectedRoute>
              <ProfilePage />
            </ProtectedRoute>
          } />

          <Route path="/admin/*" element={
            <ProtectedRoute allowedRoles={['ADMIN']}>
              <AdminDashboard />
            </ProtectedRoute>
          } />

          <Route path="/diagnostic/*" element={
            <ProtectedRoute allowedRoles={['DIAGNOSTIC', 'ADMIN']}>
              <DiagnosticDashboard />
            </ProtectedRoute>
          } />

          {/* 404 */}
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
        </RouteBoundary>
      </main>
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
}

export default App;
