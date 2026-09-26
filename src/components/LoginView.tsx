import React, { useState } from 'react';
import { LogIn, ShieldCheck } from 'lucide-react';

interface LoginViewProps {
  onLogin: (email: string, password: string) => Promise<void>;
  isLoading: boolean;
  error: string | null;
}

export const LoginView: React.FC<LoginViewProps> = ({ onLogin, isLoading, error }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!email.trim() || !password || isLoading) return;
    await onLogin(email.trim(), password);
  };

  return (
    <main className="min-h-screen bg-slate-950 text-slate-100 flex items-center justify-center px-4 py-10">
      <section
        className="w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900 p-8 shadow-2xl"
        aria-labelledby="login-title"
        data-testid="auth-login-screen"
      >
        <div className="flex items-center gap-3 mb-8">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-emerald-500/30 bg-emerald-500/10 text-sm font-bold tracking-wider text-emerald-400">
            CF
          </div>
          <div>
            <h1 id="login-title" className="text-xl font-bold tracking-tight text-white">Sign in to CarbonFlow</h1>
            <p className="mt-1 text-xs text-slate-400">Authenticate to access your organization&apos;s GHG workspace.</p>
          </div>
        </div>

        {error && (
          <div className="mb-5 rounded-lg border border-rose-800 bg-rose-950/70 px-3 py-2.5 text-xs text-rose-200" role="alert" data-testid="auth-error">
            {error}
          </div>
        )}

        <form className="space-y-4" onSubmit={handleSubmit}>
          <div>
            <label htmlFor="auth-email" className="mb-1.5 block text-xs font-medium text-slate-300">Email</label>
            <input
              id="auth-email"
              data-testid="auth-email"
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2.5 text-sm text-white outline-none transition focus:border-emerald-500"
              placeholder="you@company.com"
              disabled={isLoading}
            />
          </div>

          <div>
            <label htmlFor="auth-password" className="mb-1.5 block text-xs font-medium text-slate-300">Password</label>
            <input
              id="auth-password"
              data-testid="auth-password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-slate-800 px-3 py-2.5 text-sm text-white outline-none transition focus:border-emerald-500"
              placeholder="Enter your password"
              disabled={isLoading}
            />
          </div>

          <button
            id="auth-submit"
            data-testid="auth-submit"
            type="submit"
            disabled={isLoading}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isLoading ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" aria-hidden="true" />
            ) : (
              <LogIn className="h-4 w-4" aria-hidden="true" />
            )}
            {isLoading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="mt-6 flex items-start gap-2 border-t border-slate-800 pt-5 text-[11px] leading-relaxed text-slate-500">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" aria-hidden="true" />
          <span>Your organization and role are determined by the authenticated backend session.</span>
        </div>
      </section>
    </main>
  );
};
