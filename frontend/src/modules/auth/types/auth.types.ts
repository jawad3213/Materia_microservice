export type UserRole = 'ADMIN' | 'PURCHASER' | 'RECEIVER';

export interface User {
  id: string;
  name: string;
  email: string;
  role: UserRole;
  permissions?: string[];
  avatar?: string;
  createdAt?: string;
}

export interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
}
