/**
 * CarbonFlow — Inventory Snapshots (Phase 7 backend, workstream 8.11)
 * Lists immutable snapshots, creates them per reporting period, and locks
 * them for finalization. Amounts, status and the reproducible SHA-256 hash
 * all come from the backend.
 */
import React, { useState, useEffect, useCallback } from 'react';
import { Boxes, Camera, Lock, RefreshCw, AlertTriangle, ShieldCheck } from 'lucide-react';
import { InventorySnapshot, ReportingPeriod } from '../types.ts';
import { api } from '../services/api.ts';
import { hasPermission } from '../services/permissions.ts';

interface InventoryViewProps {
  periods: ReportingPeriod[];
  permissions: string[];
}

export const InventoryView: React.FC<InventoryViewProps> = ({ periods, permissions }) => {
  const [snapshots, setSnapshots] = useState<InventorySnapshot[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [snapshotPeriodId, setSnapshotPeriodId] = useState('');
  const [actingId, setActingId] = useState<string | null>(null);

  const canCreate = hasPermission(permissions, 'inventory.create');
  const canLock = hasPermission(permissions, 'inventory.lock');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSnapshots(await api.getInventory());
    } catch (err: any) {
      setError(err?.message || 'Failed to load inventory snapshots.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const handleCreate = async () => {
    if (!snapshotPeriodId) return;
    setActionError(null);
    setActingId(snapshotPeriodId);
    try {
      await api.createInventorySnapshot(snapshotPeriodId);
      await load();
    } catch (err: any) {
      setActionError(err?.message || 'Snapshot creation failed.');
    } finally {
      setActingId(null);
    }
  };

  const handleLock = async (snapshotId: string) => {
    setActionError(null);
    setActingId(snapshotId);
    try {
      await api.lockInventorySnapshot(snapshotId);
      await load();
    } catch (err: any) {
      setActionError(err?.message || 'Snapshot lock failed.');
    } finally {
      setActingId(null);
    }
  };

  const periodName = (id: string) => periods.find((p) => p.id === id)?.name ?? 'Unknown period';

  if (loading) {
    return <div className="p-8 text-slate-400">Loading inventory snapshots…</div>;
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
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Inventory Snapshots</h1>
          <p className="text-xs text-slate-400 mt-1">
            Immutable period inventories with reproducible SHA-256 content hashes.
          </p>
        </div>
        {canCreate && (
          <div className="flex items-center gap-2">
            <select
              value={snapshotPeriodId}
              onChange={(e) => setSnapshotPeriodId(e.target.value)}
              className="bg-slate-800 border border-slate-700 rounded-lg px-2.5 py-2 text-xs text-white focus:outline-none"
              aria-label="Reporting period for new snapshot"
            >
              <option value="">Select period…</option>
              {periods.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
            <button
              onClick={handleCreate}
              disabled={!snapshotPeriodId || actingId !== null}
              className="flex items-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition disabled:opacity-50"
            >
              <Camera className="w-4 h-4" />
              {actingId ? 'Creating…' : 'Create Snapshot'}
            </button>
          </div>
        )}
      </div>

      {actionError && (
        <div className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          {actionError}
        </div>
      )}

      {/* Snapshot table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Boxes className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Snapshot Registry ({snapshots.length})</h2>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <span>Reproducible content hashes</span>
          </div>
        </div>

        {snapshots.length === 0 ? (
          <div className="p-12 text-center">
            <Boxes className="w-8 h-8 text-slate-600 mx-auto mb-3" />
            <div className="text-sm font-semibold text-slate-300">No inventory snapshots yet</div>
            <div className="text-xs text-slate-500 mt-1">
              {canCreate
                ? 'Create a snapshot for a reporting period to freeze its emission totals.'
                : 'Your role cannot create snapshots.'}
            </div>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs text-slate-300">
              <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
                <tr>
                  <th className="px-4 py-3">Reporting Period</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3 text-right">Scope 1 (t)</th>
                  <th className="px-4 py-3 text-right">Scope 2 Location (t)</th>
                  <th className="px-4 py-3 text-right">Scope 2 Market (t)</th>
                  <th className="px-4 py-3">Content Hash</th>
                  <th className="px-4 py-3 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {snapshots.map((snapshot) => (
                  <tr key={snapshot.id} className="hover:bg-slate-800/50 transition">
                    <td className="px-4 py-3 font-semibold text-white">{periodName(snapshot.reportingPeriodId)}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold border ${
                          snapshot.status === 'ACTIVE'
                            ? 'bg-emerald-950 text-emerald-300 border-emerald-800'
                            : snapshot.status === 'LOCKED'
                            ? 'bg-purple-950 text-purple-300 border-purple-800'
                            : 'bg-slate-800 text-slate-400 border-slate-700'
                        }`}
                      >
                        {snapshot.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono">{snapshot.scope1Co2eT.toFixed(4)}</td>
                    <td className="px-4 py-3 text-right font-mono text-sky-400">{snapshot.scope2LocationCo2eT.toFixed(4)}</td>
                    <td className="px-4 py-3 text-right font-mono text-emerald-400">{snapshot.scope2MarketCo2eT.toFixed(4)}</td>
                    <td className="px-4 py-3 font-mono text-[10px] text-slate-500" title={snapshot.snapshotHash}>
                      {snapshot.snapshotHash.slice(0, 12)}…
                    </td>
                    <td className="px-4 py-3 text-right">
                      {snapshot.status === 'ACTIVE' && canLock ? (
                        <button
                          onClick={() => handleLock(snapshot.id)}
                          disabled={actingId !== null}
                          className="px-2.5 py-1 bg-purple-950 hover:bg-purple-900 text-purple-300 border border-purple-800 rounded text-[11px] font-semibold transition disabled:opacity-50"
                        >
                          <Lock className="w-3 h-3 inline mr-1" />
                          Lock
                        </button>
                      ) : (
                        <span className="text-[10px] text-slate-600">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};
