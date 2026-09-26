/**
 * CarbonFlow — Organizational Boundaries & Facilities View
 */
import React, { useState } from 'react';
import { Building2, Plus, Globe, CheckCircle2, Shield } from 'lucide-react';
import { Organization, Facility } from '../types.ts';

interface BoundariesViewProps {
  org: Organization | null;
  facilities: Facility[];
  onUpdateOrg: (data: Partial<Organization>) => void;
  onCreateFacility: (data: any) => void;
}

export const BoundariesView: React.FC<BoundariesViewProps> = ({
  org,
  facilities,
  onUpdateOrg,
  onCreateFacility,
}) => {
  const [isAddingFacility, setIsAddingFacility] = useState(false);
  const [approach, setApproach] = useState(org?.consolidationApproach || 'OPERATIONAL_CONTROL');
  const [baseYear, setBaseYear] = useState(org?.baseYear || 2023);

  const [newFacName, setNewFacName] = useState('');
  const [newFacCode, setNewFacCode] = useState('');
  const [newFacType, setNewFacType] = useState('MANUFACTURING');
  const [newFacCountry, setNewFacCountry] = useState('US');
  const [newFacGrid, setNewFacGrid] = useState('eGRID_MROW');
  const [newFacArea, setNewFacArea] = useState('');

  const handleSaveBoundaries = () => {
    onUpdateOrg({ consolidationApproach: approach as any, baseYear: Number(baseYear) });
  };

  const handleAddFacilitySubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newFacName || !newFacCode) return;
    onCreateFacility({
      name: newFacName,
      facilityCode: newFacCode,
      facilityType: newFacType,
      country: newFacCountry,
      gridRegion: newFacGrid,
      floorAreaM2: newFacArea ? Number(newFacArea) : undefined,
    });
    setIsAddingFacility(false);
    setNewFacName('');
    setNewFacCode('');
    setNewFacArea('');
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
          className="flex items-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
        >
          <Plus className="w-4 h-4" />
          Add Facility
        </button>
      </div>

      {/* Consolidation Approach Card */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <Shield className="w-4 h-4 text-emerald-400" />
          <h2 className="text-sm font-bold text-white">Consolidation Approach & Base Year</h2>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-300">Consolidation Methodology</label>
            <select
              value={approach}
              onChange={(e) => setApproach(e.target.value as any)}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-emerald-500"
            >
              <option value="OPERATIONAL_CONTROL">Operational Control (100% of sites with operating authority)</option>
              <option value="FINANCIAL_CONTROL">Financial Control (Direct financial governance)</option>
              <option value="EQUITY_SHARE">Equity Share (Economic interest percentage)</option>
            </select>
            <p className="text-[11px] text-slate-500 mt-1">
              Account for 100% of emissions from operations over which the reporting entity has full authority.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-300">Baseline Year</label>
            <input
              type="number"
              value={baseYear}
              onChange={(e) => setBaseYear(Number(e.target.value))}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-xs text-white focus:outline-none focus:border-emerald-500"
            />
            <p className="text-[11px] text-slate-500 mt-1">
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

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-slate-850 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th className="px-4 py-3">Code</th>
                <th className="px-4 py-3">Facility Name</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Country / Region</th>
                <th className="px-4 py-3">Subgrid Factor Zone</th>
                <th className="px-4 py-3 text-right">Floor Area (m²)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {facilities.map((fac) => (
                <tr key={fac.id} className="hover:bg-slate-850/50 transition">
                  <td className="px-4 py-3 font-mono font-medium text-emerald-400">{fac.facilityCode}</td>
                  <td className="px-4 py-3 font-semibold text-white">{fac.name}</td>
                  <td className="px-4 py-3">
                    <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-slate-800 text-slate-300 border border-slate-700">
                      {fac.facilityType}
                    </span>
                  </td>
                  <td className="px-4 py-3 flex items-center gap-1.5">
                    <Globe className="w-3.5 h-3.5 text-slate-500" />
                    <span>
                      {fac.country} {fac.stateProvince ? `(${fac.stateProvince})` : ''}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-mono text-slate-400">{fac.gridRegion}</td>
                  <td className="px-4 py-3 text-right font-medium text-slate-200">
                    {fac.floorAreaM2 ? fac.floorAreaM2.toLocaleString() : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Add Facility Modal */}
      {isAddingFacility && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-800 rounded-xl max-w-md w-full p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-sm font-bold text-white">Register New Facility</h3>
              <button
                onClick={() => setIsAddingFacility(false)}
                className="text-slate-400 hover:text-white text-xs font-semibold"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleAddFacilitySubmit} className="space-y-3 text-xs">
              <div>
                <label className="block text-slate-300 mb-1 font-medium">Facility Name</label>
                <input
                  required
                  value={newFacName}
                  onChange={(e) => setNewFacName(e.target.value)}
                  placeholder="e.g. Phoenix Logistics Hub"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Facility Code</label>
                  <input
                    required
                    value={newFacCode}
                    onChange={(e) => setNewFacCode(e.target.value)}
                    placeholder="FAC-PHX-04"
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                  />
                </div>
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Type</label>
                  <select
                    value={newFacType}
                    onChange={(e) => setNewFacType(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  >
                    <option value="MANUFACTURING">Manufacturing</option>
                    <option value="DATA_CENTER">Data Center</option>
                    <option value="LOGISTICS">Logistics</option>
                    <option value="OFFICE">Office</option>
                    <option value="WAREHOUSE">Warehouse</option>
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Country Code</label>
                  <input
                    required
                    value={newFacCountry}
                    onChange={(e) => setNewFacCountry(e.target.value)}
                    placeholder="US, DE, UK"
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  />
                </div>
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Grid Region Code</label>
                  <input
                    required
                    value={newFacGrid}
                    onChange={(e) => setNewFacGrid(e.target.value)}
                    placeholder="e.g. eGRID_AZNM"
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-medium">Floor Area (m² optional)</label>
                <input
                  type="number"
                  value={newFacArea}
                  onChange={(e) => setNewFacArea(e.target.value)}
                  placeholder="e.g. 15000"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setIsAddingFacility(false)}
                  className="px-3 py-2 rounded-lg text-slate-400 hover:text-white"
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
          </div>
        </div>
      )}
    </div>
  );
};
