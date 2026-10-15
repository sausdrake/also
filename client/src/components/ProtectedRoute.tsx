import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';

interface ProtectedRouteProps {
  children: ReactNode;
  allowedRoles?: string[];
}

function isTokenExpired(token: string): boolean {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return true;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (!payload?.exp) return true;
    return Date.now() / 1000 > payload.exp;
  } catch {
    return true;
  }
}

export const ProtectedRoute = ({ children, allowedRoles }: ProtectedRouteProps) => {
  const { accessToken, user, logout } = useAuthStore();

  if (!accessToken) return <Navigate to="/login" replace />;

  if (isTokenExpired(accessToken)) {
    logout();
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles && allowedRoles.length > 0) {
    const userRoles = user?.roles ?? [];
    const hasRole = allowedRoles.some(required =>
      userRoles.some(r => r === required || r === `ROLE_${required}` || r.replace('ROLE_', '') === required.replace('ROLE_', ''))
    );
    if (!hasRole) return <Navigate to="/forbidden" replace />;
  }

  return <>{children}</>;
};
