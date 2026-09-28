/**
 * CarbonFlow — Platform Administration (workstream 8.17)
 * Organization lifecycle management backed by the Java `/platform/tenants`
 * endpoints. Reachable only by PLATFORM_ADMIN (the nav item is gated on
 * `platform.tenants.read`); every action is re-authorized server-side.
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  ShieldCheck,
  CheckCircle,
  XCircle,
  PauseCircle,
  RefreshCw,
  AlertTriangle,
  Building2,
} from 'lucide-react';
import { OrganizationStatus, PlatformTenant } from '../types.ts';
import { api } from '../services/api.ts';

const STATUS_FILTERS: { value: string; label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'PENDING_ACTIVATION', label: 'Pending activation' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'SUSPENDED', label: 'Suspended' },
];

const STATUS_STYLES: Record<string, string> = {
  PENDING_ACTIVATION: 'bg-amber-950 text-amber-300 border-amber-800',
  ACTIVE: 'bg-emerald-950 text-emerald-300 border-emerald-800',
  REJECTED: 'bg-rose-950 text-rose-300 border-rose-800',
  SUSPENDED: 'bg-purple-950 text-purple-300 border-purple-800',
};

export const PlatformAdminView: React.FC = () => {
  const [tenants, setTenants] = useState<PlatformTenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [actingId, setActingId] = useState<string | null>(null);
  const [selected, setSelected] = useState<PlatformTenant | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTenants(await api.getPlatformTenants(statusFilter || undefined));
    } catch (err: any) {
      setError(err?.message || 'Failed to load organizations.');
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleAction = async (tenant: PlatformTenant, action: 'approve' | 'reject' | 'suspend') => {
    setActionError(null);
    setActingId(tenant.id);
    try {
      if (action === 'approve') {
        await api.approveTenant(tenant.id);
      } else if (action === 'reject') {
        await api.rejectTenant(tenant.id);
      } else {
        await api.suspendTenant(tenant.id);
      }
      await load();
      if (selected?.id === tenant.id) {
        setSelected(await api.getPlatformTenant(tenant.id).catch(() => null));
      }
    } catch (err: any) {
      // Includes 409 INVALID_STATUS_TRANSITION — surfaced verbatim.
      setActionError(err?.message || 'Action failed.');
    } finally {
      setActingId(null);
    }
  };

  const openDetail = async (tenant: PlatformTenant) => {
    setActionError(null);
    try {
      setSelected(await api.getPlatformTenant(tenant.id));
    } catch (err: any) {
      setActionError(err?.message || 'Failed to load organization detail.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Platform Administration</h1>
          <p className="text-xs text-slate-400 mt-1">
            Organization lifecycle management — approval, rejection, suspension and reactivation.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-2 text-xs text-white focus:outline-none"
            aria-label="Filter by lifecycle status"
          >
            {STATUS_FILTERS.map((f) => (
              <option key={f.value} value={f.value}>{f.label}</option>
            ))}
          </select>
          <button
            onClick={() => void load()}
            disabled={loading}
            className="p-2 text-slate-400 hover:text-white rounded-md hover:bg-slate-800 transition"
            title="Refresh"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin text-emerald-400' : ''}`} />
          </button>
        </div>
      </div>

      {actionError && (
        <div className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          {actionError}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Tenant list */}
        <div className="lg:col-span-2 bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
          <div className="p-4 border-b border-slate-800 flex items-center gap-2">
            <Building2 className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Organizations ({tenants.length})</h2>
          </div>

          {loading ? (
            <div className="py-8 text-center text-slate-400 text-xs">Loading organizations…</div>
          ) : error ? (
            <div className="p-4 text-xs text-rose-200">{error}</div>
          ) : tenants.length === 0 ? (
            <div className="p-12 text-center">
              <Building2 className="w-8 h-8 text-slate-600 mx-auto mb-3" />
              <div className="text-sm font-semibold text-slate-300">No organizations found</div>
              <div className="text-xs text-slate-500 mt-1">Try a different status filter.</div>
            </div>
          ) : (
            <div className="divide-y divide-slate-800">
              {tenants.map((tenant) => (
                <div
                  key={tenant.id}
                  className={`p-4 hover:bg-slate-800/50 transition flex flex-col md:flex-row md:items-center justify-between gap-3 ${
                    selected?.id === tenant.id ? 'bg-slate-800/40' : ''
                  }`}
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-white text-sm">{tenant.name}</span>
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold border ${STATUS_STYLES[tenant.status] ?? 'bg-slate-800 text-slate-400 border-slate-700'}`}
                      >
                        {tenant.status}
                      </span>
                    </div>
                    <div className="text-[11px] text-slate-500">
                      {tenant.country} · {tenant.industry} · Base year {tenant.baseYear}
                    </div>
                  </div>

                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={() => openDetail(tenant)}
                      className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] font-semibold border border-slate-700 transition"
                    >
                      Details
                    </button>
                    {tenant.status !== 'ACTIVE' && (
                      <button
                        onClick={() => handleAction(tenant, 'approve')}
                        disabled={actingId !== null}
                        className="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-950 hover:bg-emerald-900 text-emerald-300 border border-emerald-800 rounded text-[11px] font-semibold transition disabled:opacity-50"
                        title="Approve (also reactivates suspended/rejected)"
                      >
                        <CheckCircle className="w-3 h-3" /> Approve
                      </button>
                    )}
                    {tenant.status === 'PENDING_ACTIVATION' && (
                      <button
                        onClick={() => handleAction(tenant, 'reject')}
                        disabled={actingId !== null}
                        className="inline-flex items-center gap-1 px-2.5 py-1 bg-rose-950 hover:bg-rose-900 text-rose-300 border border-rose-800 rounded text-[11px] font-semibold transition disabled:opacity-50"
                      >
                        <XCircle className="w-3 h-3" /> Reject
                      </button>
                    )}
                    {tenant.status === 'ACTIVE' && (
                      <button
                        onClick={() => handleAction(tenant, 'suspend')}
                        disabled={actingId !== null}
                        className="inline-flex items-center gap-1 px-2.5 py-1 bg-purple-950 hover:bg-purple-900 text-purple-300 border border-purple-800 rounded text-[11px] font-semibold transition disabled:opacity-50"
                      >
                        <PauseCircle className="w-3 h-3" /> Suspend
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Detail panel */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm h-fit">
          <div className="flex items-center gap-2 mb-4">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Organization Detail</h2>
          </div>
          {!selected ? (
            <div className="py-6 text-center text-slate-500 text-xs">
              Select an organization to view its detail and audit trail.
            </div>
          ) : (
            <div className="space-y-3 text-xs">
              <div>
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Name</div>
                <div className="text-white font-semibold">{selected.name}</div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Country</div>
                  <div className="text-slate-200">{selected.country}</div>
                </div>
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Industry</div>
                  <div className="text-slate-200">{selected.industry}</div>
                </div>
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Consolidation</div>
                  <div className="text-slate-200">{selected.consolidationApproach.replace(/_/g, ' ')}</div>
                </div>
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Base Year</div>
                  <div className="text-slate-200">{selected.baseYear}</div>
                </div>
              </div>
              <div>
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Lifecycle Status</div>
                <span
                  className={`inline-block mt-1 px-2 py-0.5 rounded text-[10px] font-bold border ${
                    STATUS_STYLES[selected.status] ?? 'bg-slate-800 text-slate-400 border-slate-700'
                  }`}
                >
                  {selected.status}
                </span>
              </div>
              {selected.statusChangedAt && (
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Status Changed</div>
                  <div className="text-slate-200">{new Date(selected.statusChangedAt).toLocaleString()}</div>
                </div>
              )}
              {selected.statusChangedBy && (
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Changed By (user id)</div>
                  <div className="text-slate-200 font-mono text-[11px]">{selected.statusChangedBy}</div>
                </div>
              )}
              {selected.statusNote && (
                <div>
                  <div className="text-slate-500 text-[10px] uppercase font-semibold">Status Note</div>
                  <div className="text-slate-200">{selected.statusNote}</div>
                </div>
              )}
              <div>
                <div className="text-slate-500 text-[10px] uppercase font-semibold">Created</div>
                <div className="text-slate-200">{new Date(selected.createdAt).toLocaleString()}</div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
