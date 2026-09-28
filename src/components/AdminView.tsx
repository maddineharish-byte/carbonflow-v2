/**
 * CarbonFlow — Company Administration (workstream 8.16)
 * Tenant user administration backed by the Java `/users` endpoints.
 * Role changes and deactivation are backend-enforced; the frontend only
 * offers actions the caller's permissions allow.
 */
import React, { useState, useEffect, useCallback } from 'react';
import { Users, UserPlus, Ban, CheckCircle, RefreshCw, AlertTriangle } from 'lucide-react';
import { RoleName, UserAdmin, UserAdminInput } from '../types.ts';
import { api } from '../services/api.ts';
import { hasPermission } from '../services/permissions.ts';

interface AdminViewProps {
  permissions: string[];
}

const ASSIGNABLE_ROLES: RoleName[] = [
  'COMPANY_ADMIN',
  'SUSTAINABILITY_MANAGER',
  'CARBON_ACCOUNTANT',
  'DATA_OWNER',
  'FACILITY_MANAGER',
  'REVIEWER',
  'MANAGEMENT',
  'ASSURANCE_PROVIDER',
];

export const AdminView: React.FC<AdminViewProps> = ({ permissions }) => {
  const [users, setUsers] = useState<UserAdmin[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [role, setRole] = useState<RoleName>('REVIEWER');

  const canCreate = hasPermission(permissions, 'users.create');
  const canDisable = hasPermission(permissions, 'users.disable');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setUsers(await api.getUsers());
    } catch (err: any) {
      setError(err?.message || 'Failed to load users.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setActionError(null);
    const data: UserAdminInput = { email, password, fullName, role };
    try {
      await api.createUser(data);
      setIsModalOpen(false);
      setEmail('');
      setPassword('');
      setFullName('');
      setRole('REVIEWER');
      await load();
    } catch (err: any) {
      setActionError(err?.message || 'User creation failed.');
    }
  };

  const handleToggleActive = async (user: UserAdmin) => {
    setActionError(null);
    try {
      if (user.active) {
        await api.disableUser(user.id);
      } else {
        await api.enableUser(user.id);
      }
      await load();
    } catch (err: any) {
      setActionError(err?.message || 'Action failed.');
    }
  };

  if (loading) {
    return <div className="p-8 text-slate-400">Loading users…</div>;
  }

  if (error) {
    return (
      <div className="space-y-6">
        <div className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/30 flex items-center gap-3">
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" />
          <span className="text-xs text-rose-200">{error}</span>
        </div>
        <button
          onClick={() => void load()}
          className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
        >
          <RefreshCw className="w-4 h-4" /> Retry
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Company Administration</h1>
          <p className="text-xs text-slate-400 mt-1">
            Manage tenant members and their role assignments.
          </p>
        </div>
        {canCreate && (
          <button
            onClick={() => setIsModalOpen(true)}
            className="flex items-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
          >
            <UserPlus className="w-4 h-4" />
            Add User
          </button>
        )}
      </div>

      {actionError && (
        <div className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          {actionError}
        </div>
      )}

      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center gap-2">
          <Users className="w-4 h-4 text-emerald-400" />
          <h2 className="text-sm font-bold text-white">Tenant Members ({users.length})</h2>
        </div>

        {users.length === 0 ? (
          <div className="p-12 text-center">
            <Users className="w-8 h-8 text-slate-600 mx-auto mb-3" />
            <div className="text-sm font-semibold text-slate-300">No members yet</div>
            <div className="text-xs text-slate-500 mt-1">Add users to give them access to this organization.</div>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs text-slate-300">
              <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
                <tr>
                  <th className="px-4 py-3">Name</th>
                  <th className="px-4 py-3">Email</th>
                  <th className="px-4 py-3">Role</th>
                  <th className="px-4 py-3">Status</th>
                  {canDisable && <th className="px-4 py-3 text-right">Action</th>}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {users.map((user) => (
                  <tr key={user.id} className="hover:bg-slate-800/50 transition">
                    <td className="px-4 py-3 font-semibold text-white">{user.fullName}</td>
                    <td className="px-4 py-3 text-slate-400">{user.email}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-slate-800 text-slate-300 border border-slate-700">
                        {user.role}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold border ${
                          user.active
                            ? 'bg-emerald-950 text-emerald-300 border-emerald-800'
                            : 'bg-slate-800 text-slate-400 border-slate-700'
                        }`}
                      >
                        {user.active ? 'ACTIVE' : 'DISABLED'}
                      </span>
                    </td>
                    {canDisable && (
                      <td className="px-4 py-3 text-right">
                        <button
                          onClick={() => handleToggleActive(user)}
                          className={`inline-flex items-center gap-1 px-2.5 py-1 rounded text-[11px] font-semibold border transition ${
                            user.active
                              ? 'bg-rose-950 hover:bg-rose-900 text-rose-300 border-rose-800'
                              : 'bg-emerald-950 hover:bg-emerald-900 text-emerald-300 border-emerald-800'
                          }`}
                        >
                          {user.active ? (
                            <>
                              <Ban className="w-3 h-3" /> Disable
                            </>
                          ) : (
                            <>
                              <CheckCircle className="w-3 h-3" /> Enable
                            </>
                          )}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Add User Modal */}
      {isModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-800 rounded-xl max-w-md w-full p-6 shadow-2xl space-y-4 text-xs">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-sm font-bold text-white">Add Tenant Member</h3>
              <button onClick={() => setIsModalOpen(false)} className="text-slate-400 hover:text-white">✕</button>
            </div>
            <form onSubmit={handleCreate} className="space-y-3">
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Full Name</label>
                <input
                  required
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Email</label>
                <input
                  required
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Temporary Password</label>
                <input
                  required
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Minimum 8 characters"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Role</label>
                <select
                  value={role}
                  onChange={(e) => setRole(e.target.value as RoleName)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  {ASSIGNABLE_ROLES.map((r) => (
                    <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>
                  ))}
                </select>
              </div>
              <div className="flex justify-end gap-2 pt-3 border-t border-slate-800">
                <button type="button" onClick={() => setIsModalOpen(false)} className="px-3 py-2 rounded-lg text-slate-400 hover:text-white">Cancel</button>
                <button type="submit" className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition">Add User</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
