/**
 * CarbonFlow — Company Administration (workstream 8.16)
 * Tenant user administration backed by the Java `/users` endpoints.
 * Role changes and deactivation are backend-enforced; the frontend only
 * offers actions the caller's permissions allow.
 */
import React, { useState, useEffect, useCallback } from 'react';
import { Users, UserPlus, Ban, CheckCircle, RefreshCw, AlertTriangle } from 'lucide-react';
import { RoleName, UserAdmin, UserAdminInput } from '../types.ts';
import { Modal } from './Modal.tsx';
import { ConfirmDialog } from './ConfirmDialog.tsx';
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
  const [pendingDisable, setPendingDisable] = useState<UserAdmin | null>(null);

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

  // Disabling a member revokes their access, so it is confirmed explicitly.
  // Enabling is reversible and runs immediately.
  const requestToggleActive = (user: UserAdmin) => {
    setActionError(null);
    if (user.active) {
      setPendingDisable(user);
      return;
    }
    void applyToggleActive(user);
  };

  const applyToggleActive = async (user: UserAdmin) => {
    setActionError(null);
    try {
      if (user.active) {
        await api.disableUser(user.id);
      } else {
        await api.enableUser(user.id);
      }
      setPendingDisable(null);
      await load();
    } catch (err: any) {
      setActionError(err?.message || 'Action failed.');
      setPendingDisable(null);
    }
  };

  if (loading) {
    return (
      <div className="p-8 text-slate-400" role="status">
        Loading users…
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-6">
        <div
          role="alert"
          className="p-4 rounded-lg bg-rose-500/10 border border-rose-500/30 flex items-center gap-3"
        >
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0" aria-hidden="true" />
          <span className="text-xs text-rose-200">{error}</span>
        </div>
        <button
          onClick={() => void load()}
          className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
        >
          <RefreshCw className="w-4 h-4" aria-hidden="true" /> Retry
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
            aria-haspopup="dialog"
            className="flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
          >
            <UserPlus className="w-4 h-4" aria-hidden="true" />
            Add User
          </button>
        )}
      </div>

      {actionError && (
        <div
          role="alert"
          className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200"
        >
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" aria-hidden="true" />
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
          <div
              className="overflow-x-auto"
              tabIndex={0}
              role="group"
              aria-label="Tenant members, scrollable"
            >
            <table className="w-full min-w-[40rem] text-left text-xs text-slate-300">
              <caption className="sr-only">
                Tenant members with role assignment, account status, and the action available to you.
              </caption>
              <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
                <tr>
                  <th scope="col" className="px-4 py-3">Name</th>
                  <th scope="col" className="px-4 py-3">Email</th>
                  <th scope="col" className="px-4 py-3">Role</th>
                  <th scope="col" className="px-4 py-3">Status</th>
                  {canDisable && <th scope="col" className="px-4 py-3 text-right">Action</th>}
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
                          onClick={() => requestToggleActive(user)}
                          aria-label={
                            user.active
                              ? `Disable ${user.fullName}`
                              : `Enable ${user.fullName}`
                          }
                          className={`inline-flex items-center gap-1 px-2.5 py-1 rounded text-[11px] font-semibold border transition ${
                            user.active
                              ? 'bg-rose-950 hover:bg-rose-900 text-rose-300 border-rose-800'
                              : 'bg-emerald-950 hover:bg-emerald-900 text-emerald-300 border-emerald-800'
                          }`}
                        >
                          {user.active ? (
                            <>
                              <Ban className="w-3 h-3" aria-hidden="true" /> Disable
                            </>
                          ) : (
                            <>
                              <CheckCircle className="w-3 h-3" aria-hidden="true" /> Enable
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
        <Modal
          title="Add Tenant Member"
          description="The member can sign in immediately with this temporary password and role."
          onClose={() => setIsModalOpen(false)}
          closeLabel="Cancel adding tenant member"
          panelClassName="max-w-md"
        >
          <form onSubmit={handleCreate} className="space-y-3 text-xs">
            <div>
              <label htmlFor="admin-full-name" className="block text-slate-300 mb-1 font-medium">
                Full Name
              </label>
              <input
                id="admin-full-name"
                required
                autoComplete="off"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label htmlFor="admin-email" className="block text-slate-300 mb-1 font-medium">
                Email
              </label>
              <input
                id="admin-email"
                required
                type="email"
                autoComplete="off"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label htmlFor="admin-password" className="block text-slate-300 mb-1 font-medium">
                Temporary Password
              </label>
              <input
                id="admin-password"
                required
                type="password"
                autoComplete="new-password"
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Minimum 8 characters"
                aria-describedby="admin-password-hint"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
              <p id="admin-password-hint" className="mt-1 text-[11px] text-slate-500">
                At least 8 characters. The member should change it after first sign-in.
              </p>
            </div>
            <div>
              <label htmlFor="admin-role" className="block text-slate-300 mb-1 font-medium">
                Role
              </label>
              <select
                id="admin-role"
                value={role}
                onChange={(e) => setRole(e.target.value as RoleName)}
                aria-describedby="admin-role-hint"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              >
                {ASSIGNABLE_ROLES.map((r) => (
                  <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>
                ))}
              </select>
              <p id="admin-role-hint" className="mt-1 text-[11px] text-slate-500">
                The assigned role determines which workspace sections are visible.
              </p>
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsModalOpen(false)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Add User
              </button>
            </div>
          </form>
        </Modal>
      )}

      {/* Disable confirmation — revoking access is not self-evident from the
          row button, so the consequence is spelled out. */}
      {pendingDisable && (
        <ConfirmDialog
          title={`Disable ${pendingDisable.fullName}?`}
          body={
            <>
              <strong>{pendingDisable.fullName}</strong> ({pendingDisable.email}) will immediately lose access to
              this organization and cannot sign in until re-enabled. Their audit history and attributed records are
              retained.
            </>
          }
          confirmLabel="Disable member"
          confirmAriaLabel={`Confirm disabling ${pendingDisable.fullName}`}
          onConfirm={() => void applyToggleActive(pendingDisable)}
          onCancel={() => setPendingDisable(null)}
        />
      )}
    </div>
  );
};
