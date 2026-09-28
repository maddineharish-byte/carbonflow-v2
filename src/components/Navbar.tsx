/**
 * CarbonFlow — Executive Navigation Header
 * Features tenant switcher, canonical role switcher, and audit readiness status.
 */
import React from 'react';
import { Building2, ShieldCheck, UserCircle, RefreshCw, FileSpreadsheet, LogOut } from 'lucide-react';
import { RoleName, Organization, User, AuthMembership } from '../types.ts';

interface NavbarProps {
  currentOrg: Organization | null;
  currentUser: User | null;
  currentRole: RoleName | null;
  memberships: AuthMembership[];
  onSwitchTenant: (orgId: string) => void;
  onSwitchRole: (role: RoleName) => void;
  onRefresh: () => void;
  onExport: () => void;
  onLogout: () => void;
  isLoading: boolean;
}

const ROLES_LIST: { role: RoleName; label: string }[] = [
  { role: 'COMPANY_ADMIN', label: 'Company Admin' },
  { role: 'SUSTAINABILITY_MANAGER', label: 'Sustainability Manager' },
  { role: 'CARBON_ACCOUNTANT', label: 'Carbon Accountant' },
  { role: 'DATA_OWNER', label: 'Data Owner' },
  { role: 'FACILITY_MANAGER', label: 'Facility Manager' },
  { role: 'REVIEWER', label: 'GHG Reviewer / Auditor' },
  { role: 'MANAGEMENT', label: 'Executive Management' },
  { role: 'ASSURANCE_PROVIDER', label: 'Assurance Provider (3P)' },
  { role: 'PLATFORM_ADMIN', label: 'Platform Admin' },
];

export const Navbar: React.FC<NavbarProps> = ({
  currentOrg,
  currentUser,
  currentRole,
  memberships,
  onSwitchTenant,
  onSwitchRole,
  onRefresh,
  onExport,
  onLogout,
  isLoading,
}) => {
  const organizationOptions: AuthMembership[] = memberships.length > 0
    ? memberships
    : currentOrg && currentRole
      ? [{ organizationId: currentOrg.id, organizationName: currentOrg.name, role: currentRole }]
      : [];

  const roleOptions: AuthMembership[] = organizationOptions.filter(
    (membership) => membership.organizationId === currentOrg?.id
  );
  const displayedRoles = roleOptions.length > 0
    ? roleOptions
    : currentOrg && currentRole
      ? [{ organizationId: currentOrg.id, organizationName: currentOrg.name, role: currentRole }]
      : [];

  return (
    <header className="bg-slate-900 border-b border-slate-800 text-slate-100 px-6 py-3 flex items-center justify-between shadow-sm sticky top-0 z-50">
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400 font-bold tracking-wider">
            CF
          </div>
          <div>
            <div className="text-base font-bold tracking-tight text-white flex items-center gap-2">
              CarbonFlow
              <span className="text-[10px] uppercase font-semibold bg-emerald-500/20 text-emerald-300 px-1.5 py-0.5 rounded border border-emerald-500/30">
                PRO Enterprise
              </span>
            </div>
            <div className="text-xs text-slate-400">GHG accounting workspace for your organization.</div>
          </div>
        </div>

        {/* Tenant Switcher */}
        <div className="hidden md:flex items-center gap-2 bg-slate-800/80 px-3 py-1.5 rounded-lg border border-slate-700">
          <Building2 className="w-4 h-4 text-emerald-400" />
          <span className="text-xs text-slate-400 font-medium">Tenant:</span>
          <select
            id="tenant-switcher-select"
            value={currentOrg?.id || ''}
            onChange={(e) => onSwitchTenant(e.target.value)}
            disabled={isLoading || organizationOptions.length <= 1}
            className="bg-transparent text-xs font-semibold text-white focus:outline-none cursor-pointer disabled:cursor-not-allowed disabled:text-slate-500"
            aria-label="Switch tenant"
          >
            {organizationOptions.map((membership) => (
              <option key={membership.organizationId} value={membership.organizationId} className="bg-slate-800 text-white">
                {membership.organizationName}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex items-center gap-4">
        {/* Role Switcher */}
        <div className="flex items-center gap-2 bg-slate-800/80 px-3 py-1.5 rounded-lg border border-slate-700">
          <ShieldCheck className="w-4 h-4 text-sky-400" />
          <span className="text-xs text-slate-400 font-medium">Role:</span>
          <select
            id="role-switcher-select"
            value={currentRole || ''}
            onChange={(e) => onSwitchRole(e.target.value as RoleName)}
            disabled={isLoading || displayedRoles.length <= 1 || !currentOrg || !currentRole}
            className="bg-transparent text-xs font-semibold text-white focus:outline-none cursor-pointer disabled:cursor-not-allowed disabled:text-slate-500"
            aria-label="Switch role"
          >
            {displayedRoles.map((membership) => {
              const roleDefinition = ROLES_LIST.find((candidate) => candidate.role === membership.role);
              return (
                <option key={membership.role} value={membership.role} className="bg-slate-800 text-white">
                  {roleDefinition?.label || membership.role}
                </option>
              );
            })}
          </select>
        </div>

        {/* Export Button — authenticated blob download (never a browser link) */}
        <button
          onClick={onExport}
          disabled={isLoading}
          className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 transition disabled:opacity-50"
          title="Download full audited ledger as CSV"
        >
          <FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" />
          Export Ledger
        </button>

        {/* Refresh button */}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="p-1.5 text-slate-400 hover:text-white rounded-md hover:bg-slate-800 transition"
          title="Refresh Data"
        >
          <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin text-emerald-400' : ''}`} />
        </button>

        <button
          onClick={onLogout}
          disabled={isLoading}
          className="flex items-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-medium text-slate-400 transition hover:bg-slate-800 hover:text-white disabled:cursor-not-allowed disabled:opacity-50"
          title="Sign out"
          aria-label="Sign out"
        >
          <LogOut className="h-3.5 w-3.5" aria-hidden="true" />
          <span className="hidden lg:inline">Sign out</span>
        </button>

        {/* User profile chip */}
        <div className="flex items-center gap-2 pl-2 border-l border-slate-800 text-xs text-slate-300">
          <UserCircle className="w-5 h-5 text-slate-400" />
          <span className="hidden lg:inline font-medium">{currentUser?.fullName || 'Active User'}</span>
        </div>
      </div>
    </header>
  );
};
