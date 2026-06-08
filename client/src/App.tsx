import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { Header } from './components/Header';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { AuctionPage } from './pages/AuctionPage';
import { AdminDashboard } from './pages/AdminDashboard';
import { DiagnosticDashboard } from './pages/DiagnosticDashboard';
import { ProfilePage } from './pages/ProfilePage';
import { ForbiddenPage } from './pages/ForbiddenPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProtectedRoute } from './components/ProtectedRoute';

function AppContent() {
  const location = useLocation();
  const isAdminPage = location.pathname.startsWith('/admin');
  const isDiagnosticPage = location.pathname.startsWith('/diagnostic');
  const hideHeader = isAdminPage || isDiagnosticPage;

  return (
    <div className={`min-h-screen bg-gray-50 ${hideHeader ? '' : 'flex flex-col'}`}>
      {!hideHeader && <Header />}

      <main className={hideHeader ? '' : 'flex-1'}>
        <Routes>
          {/* Публичные маршруты */}
          <Route path="/login"    element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/forbidden" element={<ForbiddenPage />} />

          {/* Публичная лента */}
          <Route path="/" element={<HomePage />} />
          <Route path="/auction/:id" element={<AuctionPage />} />

          {/* Защищённые маршруты */}
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
