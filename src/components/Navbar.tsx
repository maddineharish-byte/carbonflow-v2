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

const selectClass =
  'max-w-[9.5rem] sm:max-w-[12rem] bg-transparent text-xs font-semibold text-white focus:outline-none cursor-pointer disabled:cursor-not-allowed disabled:text-slate-500';

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
    <header className="bg-slate-900 border-b border-slate-800 text-slate-100 px-3 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-3 shadow-sm sticky top-0 z-50">
      <div className="flex items-center gap-3 sm:gap-6 min-w-0">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-9 h-9 shrink-0 rounded-lg bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400 font-bold tracking-wider">
            CF
          </div>
          <div className="min-w-0">
            <div className="text-sm sm:text-base font-bold tracking-tight text-white flex items-center gap-2">
              CarbonFlow
              <span className="text-[10px] uppercase font-semibold bg-emerald-500/20 text-emerald-300 px-1.5 py-0.5 rounded border border-emerald-500/30 whitespace-nowrap">
                PRO Enterprise
              </span>
            </div>
            {/* Tagline is decorative context; it duplicates the document
                title and is hidden below sm to keep the 390px header on one
                line. */}
            <div className="hidden sm:block text-xs text-slate-400">
              GHG accounting workspace for your organization.
            </div>
          </div>
        </div>

        {/* Tenant Switcher. Always rendered (not hidden on small screens) so
            the control remains reachable and labelled at every viewport. */}
        <div className="flex items-center gap-2 max-w-[11rem] sm:max-w-none bg-slate-800/80 px-3 py-1.5 rounded-lg border border-slate-700">
          <Building2 className="w-4 h-4 shrink-0 text-emerald-400" aria-hidden="true" />
          <label htmlFor="tenant-switcher-select" className="sr-only">
            Tenant
          </label>
          <select
            id="tenant-switcher-select"
            value={currentOrg?.id || ''}
            onChange={(e) => onSwitchTenant(e.target.value)}
            disabled={isLoading || organizationOptions.length <= 1}
            className={selectClass}
            aria-label="Switch tenant"
            title={
              organizationOptions.length > 1
                ? 'Switch the active organization'
                : 'You are assigned to a single organization'
            }
          >
            {organizationOptions.map((membership) => (
              <option key={membership.organizationId} value={membership.organizationId} className="bg-slate-800 text-white">
                {membership.organizationName}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex items-center gap-2 sm:gap-4 flex-wrap">
        {/* Role Switcher */}
        <div className="flex items-center gap-2 max-w-[11rem] sm:max-w-none bg-slate-800/80 px-3 py-1.5 rounded-lg border border-slate-700">
          <ShieldCheck className="w-4 h-4 shrink-0 text-sky-400" aria-hidden="true" />
          <label htmlFor="role-switcher-select" className="sr-only">
            Role
          </label>
          <select
            id="role-switcher-select"
            value={currentRole || ''}
            onChange={(e) => onSwitchRole(e.target.value as RoleName)}
            disabled={isLoading || displayedRoles.length <= 1 || !currentOrg || !currentRole}
            className={selectClass}
            aria-label="Switch role"
            title={
              displayedRoles.length > 1
                ? 'Switch your assigned role in this organization'
                : 'You hold a single role in this organization'
            }
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

        {/* Export Ledger — authenticated blob download (never a browser link) */}
        <button
          onClick={onExport}
          disabled={isLoading}
          className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 transition disabled:opacity-50 disabled:cursor-not-allowed"
          title="Download full audited ledger as CSV"
        >
          <FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" aria-hidden="true" />
          Export Ledger
        </button>

        {/* Refresh — icon-only, so the accessible name comes from aria-label.
            The spinning icon is decorative; `isLoading` is also exposed on the
            control via aria-busy rather than by motion alone. */}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="p-1.5 text-slate-400 hover:text-white rounded-md hover:bg-slate-800 transition disabled:cursor-not-allowed disabled:opacity-50"
          title="Refresh Data"
          aria-label="Refresh data"
          aria-busy={isLoading || undefined}
        >
          <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin text-emerald-400' : ''}`} aria-hidden="true" />
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
          <UserCircle className="w-5 h-5 shrink-0 text-slate-400" aria-hidden="true" />
          <span className="hidden lg:inline font-medium">{currentUser?.fullName || 'Active User'}</span>
        </div>
      </div>
    </header>
  );
};