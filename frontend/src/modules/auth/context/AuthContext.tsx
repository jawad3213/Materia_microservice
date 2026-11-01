import { createContext, useState, useEffect, type ReactNode } from 'react';
import type { User, UserRole } from '../types/auth.types';
import authService from '../services/authService';
import { SESSION_EXPIRED_EVENT } from '../../../shared/api/axiosClient';

export interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  /** Keycloak's sign-in page, then back to this path (the dashboard by default). */
  login: (redirectPath?: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Keycloak's page to choose a new password. */
  changePassword: () => Promise<void>;
  updateUser: (changes: Partial<User>) => void;
  hasRole: (roles: UserRole | UserRole[]) => boolean;
  hasPermission: (permission: string) => boolean;
  clearError: () => void;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => authService.getStoredUser());
  const [isLoading, setIsLoading] = useState<boolean>(() => !authService.getStoredUser());
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;

    // Restores the Keycloak session without showing a page; signed out when there is none
    authService.init().then((restored) => {
      if (isMounted) {
        setUser(restored);
        setIsLoading(false);
      }
    });

    // The session can no longer be renewed: protected routes stop rendering at once
    const handleSessionExpired = () => {
      if (isMounted) {
        setUser(null);
      }
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);

    return () => {
      isMounted = false;
      window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
    };
  }, []);

  const redirecting = (action: () => Promise<void>, failure: string) => async () => {
    setError(null);
    try {
      await action();
    } catch (err: unknown) {
      setError(err instanceof Error && err.message ? err.message : failure);
      throw err;
    }
  };

  const login = (redirectPath?: string) =>
    redirecting(() => authService.login(redirectPath), 'Sign-in is unavailable')();

  const logout = async () => {
    setUser(null);
    await authService.logout();
  };

  const changePassword = redirecting(() => authService.changePassword(), 'Password change is unavailable');

  const updateUser = (changes: Partial<User>) => {
    if (user) {
      const updated = { ...user, ...changes };
      setUser(updated);
      authService.storeUser(updated);
    }
  };

  const clearError = () => setError(null);

  const hasRole = (roles: UserRole | UserRole[]): boolean => {
    if (!user) return false;
    const roleList = Array.isArray(roles) ? roles : [roles];
    return roleList.includes(user.role);
  };

  const hasPermission = (permission: string): boolean => {
    if (!user) return false;
    if (user.role === 'ADMIN') return true;
    if (user.permissions && user.permissions.includes(permission)) return true;

    // Canonical permission matrix fallback, for a user whose token carries no permission list
    const rolePermissions: Record<UserRole, string[]> = {
      ADMIN: ['*'],
      PURCHASER: [
        'category:read', 'category:write',
        'material:read', 'supplier:read', 'supplier:write',
        'requisition:read', 'requisition:write', 'requisition:validate', 'requisition:convert',
        'order:read', 'order:write', 'order:cancel', 'receipt:read',
        'invoice:read', 'invoice:write', 'payment:read'
      ],
      RECEIVER: [
        'category:read', 'material:read', 'material:stock:read', 'material:stock:write',
        'supplier:read', 'requisition:read',
        'order:read', 'receipt:read', 'receipt:write', 'receipt:quality'
      ]
    };
    return rolePermissions[user.role]?.includes(permission) ?? false;
  };

  const value: AuthContextType = {
    user,
    isAuthenticated: !!user,
    isLoading,
    error,
    login,
    logout,
    changePassword,
    updateUser,
    hasRole,
    hasPermission,
    clearError,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export default AuthContext;
