/**
 * CarbonFlow — Organizational Boundaries & Facilities View
 */
import React, { useState } from 'react';
import { Building2, Plus, Globe, Shield, Calendar } from 'lucide-react';
import { Organization, Facility } from '../types.ts';
import { Modal } from './Modal.tsx';

interface BoundariesViewProps {
  org: Organization | null;
  facilities: Facility[];
  onUpdateOrg: (data: Partial<Organization>) => void;
  onCreateFacility: (data: any) => void;
  onCreatePeriod: (data: { name: string; startDate: string; endDate: string }) => void;
}

export const BoundariesView: React.FC<BoundariesViewProps> = ({
  org,
  facilities,
  onUpdateOrg,
  onCreateFacility,
  onCreatePeriod,
}) => {
  const [isAddingFacility, setIsAddingFacility] = useState(false);
  const [approach, setApproach] = useState(org?.consolidationApproach || 'OPERATIONAL_CONTROL');
  const [baseYear, setBaseYear] = useState(org?.baseYear || 2023);

  const [newFacName, setNewFacName] = useState('');
  const [newFacCode, setNewFacCode] = useState('');
  const [newFacType, setNewFacType] = useState('');
  const [newFacCountry, setNewFacCountry] = useState('');
  const [newFacGrid, setNewFacGrid] = useState('');
  const [newFacArea, setNewFacArea] = useState('');

  // New reporting period form state
  const [isAddingPeriod, setIsAddingPeriod] = useState(false);
  const [newPeriodName, setNewPeriodName] = useState('');
  const [newPeriodStart, setNewPeriodStart] = useState('');
  const [newPeriodEnd, setNewPeriodEnd] = useState('');

  const handleSaveBoundaries = () => {
    onUpdateOrg({ consolidationApproach: approach as any, baseYear: Number(baseYear) });
  };

  const handleAddFacilitySubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newFacName || !newFacCode) return;
    onCreateFacility({
      name: newFacName,
      facilityCode: newFacCode,
      facilityType: newFacType || undefined,
      country: newFacCountry || undefined,
      gridRegion: newFacGrid || undefined,
      floorAreaM2: newFacArea ? Number(newFacArea) : undefined,
    });
    setIsAddingFacility(false);
    setNewFacName('');
    setNewFacCode('');
    setNewFacType('');
    setNewFacCountry('');
    setNewFacGrid('');
    setNewFacArea('');
  };

  const handleAddPeriodSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onCreatePeriod({
      name: newPeriodName,
      startDate: newPeriodStart,
      endDate: newPeriodEnd,
    });
    setIsAddingPeriod(false);
    setNewPeriodName('');
    setNewPeriodStart('');
    setNewPeriodEnd('');
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Organizational Boundaries & Facilities</h1>
          <p className="text-xs text-slate-400 mt-1">
            Configure consolidation approach (GHG Protocol Chapter 3) and manage active reporting facilities.
          </p>
        </div>
        <button
          onClick={() => setIsAddingFacility(true)}
          aria-haspopup="dialog"
          className="flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
        >
          <Plus className="w-4 h-4" aria-hidden="true" />
          Add Facility
        </button>
      </div>

      {/* Consolidation Approach Card */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 sm:p-6 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <Shield className="w-4 h-4 text-emerald-400" aria-hidden="true" />
          <h2 className="text-sm font-bold text-white">Consolidation Approach &amp; Base Year</h2>
        </div>

        <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
          <div className="space-y-1.5">
            <label htmlFor="boundary-approach" className="block text-xs font-medium text-slate-300">
              Consolidation Methodology
            </label>
            <select
              id="boundary-approach"
              value={approach}
              onChange={(e) => setApproach(e.target.value as any)}
              aria-describedby="boundary-approach-hint"
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-emerald-500"
            >
              <option value="OPERATIONAL_CONTROL">Operational Control (100% of sites with operating authority)</option>
              <option value="FINANCIAL_CONTROL">Financial Control (Direct financial governance)</option>
              <option value="EQUITY_SHARE">Equity Share (Economic interest percentage)</option>
            </select>
            <p id="boundary-approach-hint" className="text-[11px] text-slate-500 mt-1">
              Account for 100% of emissions from operations over which the reporting entity has full authority.
            </p>
          </div>

          <div className="space-y-1.5">
            <label htmlFor="boundary-base-year" className="block text-xs font-medium text-slate-300">
              Baseline Year
            </label>
            <input
              id="boundary-base-year"
              type="number"
              value={baseYear}
              onChange={(e) => setBaseYear(Number(e.target.value))}
              aria-describedby="boundary-base-year-hint"
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-emerald-500"
            />
            <p id="boundary-base-year-hint" className="text-[11px] text-slate-500 mt-1">
              Historical reference year against which emissions reduction targets are measured.
            </p>
          </div>

          <div className="flex items-end">
            <button
              onClick={handleSaveBoundaries}
              className="w-full sm:w-auto px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 text-xs font-semibold rounded-lg transition"
            >
              Save Boundary Settings
            </button>
          </div>
        </div>
      </div>

      {/* Facilities Directory */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Building2 className="w-4 h-4 text-sky-400" />
            <h2 className="text-sm font-bold text-white">Reporting Facilities Directory ({facilities.length})</h2>
          </div>
          <span className="text-xs text-slate-500">Bound to Organization ID: {org?.id}</span>
        </div>

        <div
          className="overflow-x-auto"
          tabIndex={0}
          role="group"
          aria-label="Reporting facilities directory, scrollable"
        >
          <table className="w-full min-w-[44rem] text-left text-xs text-slate-300">
            <caption className="sr-only">
              Reporting facilities registered to this organization, with grid region and floor area.
            </caption>
            <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th scope="col" className="px-4 py-3">Code</th>
                <th scope="col" className="px-4 py-3">Facility Name</th>
                <th scope="col" className="px-4 py-3">Type</th>
                <th scope="col" className="px-4 py-3">Country / Region</th>
                <th scope="col" className="px-4 py-3">Subgrid Factor Zone</th>
                <th scope="col" className="px-4 py-3 text-right">Floor Area (m²)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {facilities.length === 0 ? (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center">
                    <Building2 className="w-8 h-8 text-slate-600 mx-auto mb-3" />
                    <div className="text-sm font-semibold text-slate-300">No facilities registered</div>
                    <div className="text-xs text-slate-500 mt-1">
                      Add a reporting facility to begin scoping activity data.
                    </div>
                  </td>
                </tr>
              ) : (
                facilities.map((fac) => (
                  <tr key={fac.id} className="hover:bg-slate-800/50 transition">
                    <td className="px-4 py-3 font-mono font-medium text-emerald-400">{fac.facilityCode}</td>
                    <td className="px-4 py-3 font-semibold text-white">{fac.name}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-slate-800 text-slate-300 border border-slate-700">
                        {fac.facilityType}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5">
                        <Globe className="w-3.5 h-3.5 text-slate-500" aria-hidden="true" />
                        <span>
                          {fac.country} {fac.stateProvince ? `(${fac.stateProvince})` : ''}
                        </span>
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono text-slate-400">{fac.gridRegion}</td>
                    <td className="px-4 py-3 text-right font-medium text-slate-200">
                      {fac.floorAreaM2 ? fac.floorAreaM2.toLocaleString() : '—'}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Reporting Periods */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
          <div className="flex items-center gap-2">
            <Calendar className="w-4 h-4 text-sky-400" aria-hidden="true" />
            <h2 className="text-sm font-bold text-white">Reporting Periods</h2>
          </div>
          <button
            onClick={() => setIsAddingPeriod(true)}
            className="flex items-center justify-center gap-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-xs font-semibold border border-slate-700 transition"
          >
            <Plus className="w-3.5 h-3.5" aria-hidden="true" />
            New Period
          </button>
        </div>

        {isAddingPeriod ? (
          <form
            onSubmit={handleAddPeriodSubmit}
            className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 items-end text-xs"
          >
            <div>
              <label htmlFor="period-name" className="block text-slate-300 mb-1 font-medium">
                Name
              </label>
              <input
                id="period-name"
                required
                value={newPeriodName}
                onChange={(e) => setNewPeriodName(e.target.value)}
                placeholder="e.g. FY2026"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label htmlFor="period-start" className="block text-slate-300 mb-1 font-medium">
                Start Date
              </label>
              <input
                id="period-start"
                required
                type="date"
                value={newPeriodStart}
                onChange={(e) => setNewPeriodStart(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div>
              <label htmlFor="period-end" className="block text-slate-300 mb-1 font-medium">
                End Date
              </label>
              <input
                id="period-end"
                required
                type="date"
                value={newPeriodEnd}
                onChange={(e) => setNewPeriodEnd(e.target.value)}
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>
            <div className="flex flex-col-reverse sm:flex-row sm:items-center gap-2 sm:col-span-2 lg:col-span-1">
              <button
                type="button"
                onClick={() => setIsAddingPeriod(false)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Create
              </button>
            </div>
          </form>
        ) : (
          <p className="text-xs text-slate-500">
            Reporting periods are selected when logging activity data. Create a period to begin collecting data for a new reporting cycle.
          </p>
        )}
      </div>

      {/* Add Facility Modal */}
      {isAddingFacility && (
        <Modal
          title="Register New Facility"
          description="A facility becomes the organizational boundary for activity data and inventory snapshots."
          onClose={() => setIsAddingFacility(false)}
          closeLabel="Cancel registering facility"
        >
          <form onSubmit={handleAddFacilitySubmit} className="space-y-3 text-xs">
            <div>
              <label htmlFor="facility-name" className="block text-slate-300 mb-1 font-medium">
                Facility Name
              </label>
              <input
                id="facility-name"
                required
                value={newFacName}
                onChange={(e) => setNewFacName(e.target.value)}
                placeholder="e.g. Phoenix Logistics Hub"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="facility-code" className="block text-slate-300 mb-1 font-medium">
                  Facility Code
                </label>
                <input
                  id="facility-code"
                  required
                  value={newFacCode}
                  onChange={(e) => setNewFacCode(e.target.value)}
                  placeholder="FAC-PHX-04"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="facility-type" className="block text-slate-300 mb-1 font-medium">
                  Type
                </label>
                <select
                  id="facility-type"
                  value={newFacType}
                  onChange={(e) => setNewFacType(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="">Select type…</option>
                  <option value="MANUFACTURING">Manufacturing</option>
                  <option value="DATA_CENTER">Data Center</option>
                  <option value="LOGISTICS">Logistics</option>
                  <option value="OFFICE">Office</option>
                  <option value="WAREHOUSE">Warehouse</option>
                </select>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="facility-country" className="block text-slate-300 mb-1 font-medium">
                  Country Code
                </label>
                <input
                  id="facility-country"
                  required
                  value={newFacCountry}
                  onChange={(e) => setNewFacCountry(e.target.value)}
                  placeholder="US, DE, UK"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="facility-grid" className="block text-slate-300 mb-1 font-medium">
                  Grid Region Code
                </label>
                <input
                  id="facility-grid"
                  required
                  value={newFacGrid}
                  onChange={(e) => setNewFacGrid(e.target.value)}
                  placeholder="e.g. eGRID_AZNM"
                  aria-describedby="facility-grid-hint"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                />
                <p id="facility-grid-hint" className="mt-1 text-[11px] text-slate-500">
                  Subgrid zone used to select Scope 2 emission factors.
                </p>
              </div>
            </div>

            <div>
              <label htmlFor="facility-area" className="block text-slate-300 mb-1 font-medium">
                Floor Area (m² optional)
              </label>
              <input
                id="facility-area"
                type="number"
                value={newFacArea}
                onChange={(e) => setNewFacArea(e.target.value)}
                placeholder="e.g. 15000"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-end gap-2 pt-3 border-t border-slate-800">
              <button
                type="button"
                onClick={() => setIsAddingFacility(false)}
                className="px-3 py-2 rounded-lg text-slate-300 hover:text-white transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
              >
                Register Facility
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
};
