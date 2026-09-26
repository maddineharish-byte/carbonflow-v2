import React, { type ReactNode } from 'react';
import type { AuthState } from '../types.ts';
import { LoginView } from './LoginView.tsx';

interface AuthBoundaryProps {
  state: AuthState;
  isLoading: boolean;
  error: string | null;
  onLogin: (email: string, password: string) => Promise<void>;
  children: ReactNode;
}

export const AuthBoundary: React.FC<AuthBoundaryProps> = ({ state, isLoading, error, onLogin, children }) => {
  if (state === 'AUTH_LOADING') {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-950 px-4 text-sm text-slate-400" role="status">
        Checking authenticated session…
      </main>
    );
  }

  if (state === 'UNAUTHENTICATED') {
    return <LoginView onLogin={onLogin} isLoading={isLoading} error={error} />;
  }

  return <>{children}</>;
};
