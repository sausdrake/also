import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { isAccessTokenExpired } from '../auth/session';

interface ProtectedRouteProps {
  children: ReactNode;
  allowedRoles?: string[];
}

export const ProtectedRoute = ({ children, allowedRoles }: ProtectedRouteProps) => {
  const { accessToken, user, logout } = useAuthStore();
  // G1: the login form returns here after signing in (LoginPage accepts only an in-app path)
  const { pathname, search, hash } = useLocation();
  const toLogin = <Navigate to="/login" replace state={{ from: { pathname, search, hash } }} />;

  if (!accessToken) return toLogin;

  if (isAccessTokenExpired(accessToken)) {
    logout();
    return toLogin;
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
