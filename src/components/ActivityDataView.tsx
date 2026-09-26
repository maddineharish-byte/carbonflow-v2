/**
 * CarbonFlow — Activity Data Collection & Ingestion Ledger
 */
import React, { useState } from 'react';
import { Database, Plus, Play, Paperclip, FileText, CheckCircle, Clock } from 'lucide-react';
import { ActivityDataItem, Facility, ReportingPeriod } from '../types.ts';

interface ActivityDataViewProps {
  activities: ActivityDataItem[];
  facilities: Facility[];
  periods: ReportingPeriod[];
  onAddActivity: (data: any) => void;
  onRunCalculation: (activityId: string) => void;
  onBatchCalculate: () => void;
  onUploadEvidenceForActivity: (activityId: string) => void;
}

export const ActivityDataView: React.FC<ActivityDataViewProps> = ({
  activities,
  facilities,
  periods,
  onAddActivity,
  onRunCalculation,
  onBatchCalculate,
  onUploadEvidenceForActivity,
}) => {
  const [filterScope, setFilterScope] = useState<string>('ALL');
  const [filterFacility, setFilterFacility] = useState<string>('ALL');
  const [isModalOpen, setIsModalOpen] = useState(false);

  // New activity form states
  const [selectedFacility, setSelectedFacility] = useState(facilities[0]?.id || '');
  const [selectedPeriod, setSelectedPeriod] = useState(periods[0]?.id || '');
  const [scope, setScope] = useState<'SCOPE_1' | 'SCOPE_2'>('SCOPE_1');
  const [category, setCategory] = useState('STATIONARY_COMBUSTION');
  const [activityType, setActivityType] = useState('NATURAL_GAS');
  const [quantity, setQuantity] = useState('');
  const [unit, setUnit] = useState('kWh');
  const [source, setSource] = useState('Utility Meter Invoice');
  const [startDate, setStartDate] = useState('2024-01-01');
  const [endDate, setEndDate] = useState('2024-12-31');
  const [notes, setNotes] = useState('');

  const filtered = activities.filter((a) => {
    if (filterScope !== 'ALL' && a.scope !== filterScope) return false;
    if (filterFacility !== 'ALL' && a.facilityId !== filterFacility) return false;
    return true;
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onAddActivity({
      facilityId: selectedFacility || facilities[0]?.id,
      reportingPeriodId: selectedPeriod || periods[0]?.id,
      scope,
      category,
      activityType,
      quantity: Number(quantity),
      unit,
      source,
      startDate,
      endDate,
      notes,
    });
    setIsModalOpen(false);
    setQuantity('');
    setNotes('');
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Activity Data Collection Ledger</h1>
          <p className="text-xs text-slate-400 mt-1">
            Auditable operational activity ingestion across fuel, utility meters, and fleet logs.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={onBatchCalculate}
            className="flex items-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
          >
            <Play className="w-4 h-4 text-emerald-400" />
            Batch Run Calculations
          </button>
          <button
            onClick={() => setIsModalOpen(true)}
            className="flex items-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
          >
            <Plus className="w-4 h-4" />
            Log Activity
          </button>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-wrap items-center gap-4 text-xs">
        <div className="flex items-center gap-2">
          <span className="text-slate-400 font-medium">Scope:</span>
          <select
            value={filterScope}
            onChange={(e) => setFilterScope(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-md px-2.5 py-1.5 text-white focus:outline-none"
          >
            <option value="ALL">All Scopes</option>
            <option value="SCOPE_1">Scope 1 (Direct)</option>
            <option value="SCOPE_2">Scope 2 (Electricity)</option>
          </select>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-slate-400 font-medium">Facility:</span>
          <select
            value={filterFacility}
            onChange={(e) => setFilterFacility(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-md px-2.5 py-1.5 text-white focus:outline-none"
          >
            <option value="ALL">All Facilities</option>
            {facilities.map((fac) => (
              <option key={fac.id} value={fac.id}>
                {fac.name} ({fac.facilityCode})
              </option>
            ))}
          </select>
        </div>

        <div className="ml-auto text-slate-500">Showing {filtered.length} activity records</div>
      </div>

      {/* Activity Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-slate-850 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th className="px-4 py-3">Facility</th>
                <th className="px-4 py-3">Scope & Category</th>
                <th className="px-4 py-3">Activity Type</th>
                <th className="px-4 py-3 text-right">Quantity</th>
                <th className="px-4 py-3">Unit</th>
                <th className="px-4 py-3">Evidence Document</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {filtered.map((act) => (
                <tr key={act.id} className="hover:bg-slate-850/50 transition">
                  <td className="px-4 py-3 font-medium text-white">{act.facilityName}</td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold mr-1.5 ${
                        act.scope === 'SCOPE_1'
                          ? 'bg-orange-950 text-orange-300 border border-orange-800'
                          : 'bg-sky-950 text-sky-300 border border-sky-800'
                      }`}
                    >
                      {act.scope}
                    </span>
                    <span className="text-slate-400">{act.category}</span>
                  </td>
                  <td className="px-4 py-3 font-mono text-slate-300">{act.activityType}</td>
                  <td className="px-4 py-3 text-right font-mono font-semibold text-white">
                    {act.quantity.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-slate-400">{act.unit}</td>
                  <td className="px-4 py-3">
                    {act.evidence ? (
                      <div className="flex items-center gap-1.5 text-emerald-400" title={act.evidence.sha256Hash}>
                        <FileText className="w-3.5 h-3.5 shrink-0" />
                        <span className="truncate max-w-[130px] font-mono text-[11px]">{act.evidence.fileName}</span>
                      </div>
                    ) : (
                      <button
                        onClick={() => onUploadEvidenceForActivity(act.id)}
                        className="flex items-center gap-1 text-slate-500 hover:text-sky-400 text-[11px]"
                      >
                        <Paperclip className="w-3.5 h-3.5" />
                        Attach Bill
                      </button>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                        act.status === 'CALCULATED'
                          ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                          : 'bg-slate-800 text-slate-400 border border-slate-700'
                      }`}
                    >
                      {act.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button
                      onClick={() => onRunCalculation(act.id)}
                      className="px-2.5 py-1 bg-slate-800 hover:bg-emerald-600 text-slate-300 hover:text-white rounded text-[11px] font-semibold border border-slate-700 hover:border-emerald-500 transition"
                    >
                      Calculate
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Log Activity Modal */}
      {isModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-slate-900 border border-slate-800 rounded-xl max-w-lg w-full p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-sm font-bold text-white">Log Operational Activity Data</h3>
              <button onClick={() => setIsModalOpen(false)} className="text-slate-400 hover:text-white">
                ✕
              </button>
            </div>

            <form onSubmit={handleSubmit} className="space-y-3 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Facility</label>
                  <select
                    value={selectedFacility}
                    onChange={(e) => setSelectedFacility(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  >
                    {facilities.map((fac) => (
                      <option key={fac.id} value={fac.id}>
                        {fac.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Reporting Period</label>
                  <select
                    value={selectedPeriod}
                    onChange={(e) => setSelectedPeriod(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  >
                    {periods.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">GHG Scope</label>
                  <select
                    value={scope}
                    onChange={(e) => {
                      const s = e.target.value as any;
                      setScope(s);
                      if (s === 'SCOPE_1') {
                        setCategory('STATIONARY_COMBUSTION');
                        setActivityType('NATURAL_GAS');
                        setUnit('kWh');
                      } else {
                        setCategory('ELECTRICITY_LOCATION');
                        setActivityType('GRID_ELECTRICITY_US');
                        setUnit('kWh');
                      }
                    }}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  >
                    <option value="SCOPE_1">Scope 1 (Direct)</option>
                    <option value="SCOPE_2">Scope 2 (Electricity)</option>
                  </select>
                </div>

                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Activity Category</label>
                  <select
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                  >
                    {scope === 'SCOPE_1' ? (
                      <>
                        <option value="STATIONARY_COMBUSTION">Stationary Combustion (Boilers, Gensets)</option>
                        <option value="MOBILE_COMBUSTION">Mobile Combustion (Fleet Vehicles)</option>
                        <option value="FUGITIVE_EMISSIONS">Fugitive Emissions (Refrigerants)</option>
                      </>
                    ) : (
                      <>
                        <option value="ELECTRICITY_LOCATION">Purchased Electricity (Location-Based)</option>
                        <option value="ELECTRICITY_MARKET">Purchased Electricity (Market-Based/PPA)</option>
                      </>
                    )}
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-300 mb-1 font-medium">Activity Type</label>
                  <select
                    value={activityType}
                    onChange={(e) => setActivityType(e.target.value)}
                    className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                  >
                    {scope === 'SCOPE_1' ? (
                      <>
                        <option value="NATURAL_GAS">NATURAL_GAS</option>
                        <option value="DIESEL_GENERATOR">DIESEL_GENERATOR</option>
                        <option value="FLEET_DIESEL">FLEET_DIESEL</option>
                        <option value="REFRIGERANT_R410A">REFRIGERANT_R410A</option>
                      </>
                    ) : (
                      <>
                        <option value="GRID_ELECTRICITY_US">GRID_ELECTRICITY_US</option>
                        <option value="GREEN_POWER_TARIFF">GREEN_POWER_TARIFF</option>
                        <option value="RESIDUAL_MIX_US">RESIDUAL_MIX_US</option>
                      </>
                    )}
                  </select>
                </div>

                <div className="grid grid-cols-2 gap-2">
                  <div>
                    <label className="block text-slate-300 mb-1 font-medium">Quantity</label>
                    <input
                      required
                      type="number"
                      step="any"
                      value={quantity}
                      onChange={(e) => setQuantity(e.target.value)}
                      placeholder="e.g. 50000"
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white font-mono focus:outline-none focus:border-emerald-500"
                    />
                  </div>
                  <div>
                    <label className="block text-slate-300 mb-1 font-medium">Unit</label>
                    <select
                      value={unit}
                      onChange={(e) => setUnit(e.target.value)}
                      className="w-full bg-slate-800 border border-slate-700 rounded-lg px-2 py-2 text-white focus:outline-none focus:border-emerald-500"
                    >
                      <option value="kWh">kWh</option>
                      <option value="MWh">MWh</option>
                      <option value="Therms">Therms</option>
                      <option value="Litres">Litres</option>
                      <option value="Gallons">Gallons</option>
                      <option value="KG">KG</option>
                      <option value="Metric Tonnes">Metric Tonnes</option>
                    </select>
                  </div>
                </div>
              </div>

              <div>
                <label className="block text-slate-300 mb-1 font-medium">Primary Source Reference</label>
                <input
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                  placeholder="e.g. DTE Energy Meter Statement #88204"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-slate-800">
                <button
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  className="px-3 py-2 rounded-lg text-slate-400 hover:text-white"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-semibold rounded-lg transition"
                >
                  Save Activity Data
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
