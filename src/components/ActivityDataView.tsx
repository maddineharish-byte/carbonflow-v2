/**
 * CarbonFlow — Activity Data Collection & Ingestion Ledger
 */
import React, { useState } from 'react';
import { Database, Plus, Play, Paperclip, FileText } from 'lucide-react';
import { ActivityDataItem, Facility, ReportingPeriod } from '../types.ts';
import { Modal } from './Modal.tsx';

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
  // Dates default to the selected reporting period's span — no hardcoded dates.
  const [startDate, setStartDate] = useState(periods[0]?.startDate || '');
  const [endDate, setEndDate] = useState(periods[0]?.endDate || '');
  const [scope, setScope] = useState<'SCOPE_1' | 'SCOPE_2'>('SCOPE_1');
  const [category, setCategory] = useState('STATIONARY_COMBUSTION');
  const [activityType, setActivityType] = useState('NATURAL_GAS');
  const [quantity, setQuantity] = useState('');
  const [unit, setUnit] = useState('kWh');
  const [source, setSource] = useState('Utility Meter Invoice');
  const [notes, setNotes] = useState('');

  const handlePeriodChange = (periodId: string) => {
    setSelectedPeriod(periodId);
    const period = periods.find((p) => p.id === periodId);
    if (period) {
      setStartDate(period.startDate);
      setEndDate(period.endDate);
    }
  };

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
        {/* Buttons stack full-width at 390px; each opens a dialog whose label is the
            button's own accessible name. */}
        <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
          <button
            onClick={onBatchCalculate}
            className="flex items-center justify-center gap-2 px-3.5 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold rounded-lg border border-slate-700 transition"
          >
            <Play className="w-4 h-4 text-emerald-400" aria-hidden="true" />
            Batch Run Calculations
          </button>
          <button
            onClick={() => setIsModalOpen(true)}
            aria-haspopup="dialog"
            className="flex items-center justify-center gap-2 px-3.5 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition"
          >
            <Plus className="w-4 h-4" aria-hidden="true" />
            Log Activity
          </button>
        </div>
      </div>

      {/* Filter Bar. Labels are real <label htmlFor> elements (previously
          unassociated <span>s), and the result count is a live region so a
          screen-reader user hears the effect of changing a filter. */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-wrap items-end gap-4 text-xs">
        <div className="flex flex-col gap-1">
          <label htmlFor="activity-filter-scope" className="text-slate-400 font-medium">
            Scope
          </label>
          <select
            id="activity-filter-scope"
            value={filterScope}
            onChange={(e) => setFilterScope(e.target.value)}
            className="bg-slate-800 border border-slate-700 rounded-md px-2.5 py-1.5 text-white focus:outline-none"
          >
            <option value="ALL">All Scopes</option>
            <option value="SCOPE_1">Scope 1 (Direct)</option>
            <option value="SCOPE_2">Scope 2 (Electricity)</option>
          </select>
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="activity-filter-facility" className="text-slate-400 font-medium">
            Facility
          </label>
          <select
            id="activity-filter-facility"
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

        <p aria-live="polite" className="sm:ml-auto text-slate-500 pb-1.5">
          Showing {filtered.length} activity records
        </p>
      </div>

      {/* Activity Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        {/* Horizontal scroll is intentional for the wide ledger. The region is
            focusable and labelled so keyboard users can reach the scroll
            container, which is a WCAG 2.1.1 requirement for scrollable content. */}
        <div
          className="overflow-x-auto"
          tabIndex={0}
          role="group"
          aria-label="Activity data ledger, scrollable"
        >
          <table className="w-full min-w-[56rem] text-left text-xs text-slate-300">
            <caption className="sr-only">
              Activity data records with facility, scope, quantity, evidence attachment, and calculation status.
            </caption>
            <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th scope="col" className="px-4 py-3">Facility</th>
                <th scope="col" className="px-4 py-3">Scope &amp; Category</th>
                <th scope="col" className="px-4 py-3">Activity Type</th>
                <th scope="col" className="px-4 py-3 text-right">Quantity</th>
                <th scope="col" className="px-4 py-3">Unit</th>
                <th scope="col" className="px-4 py-3">Evidence Document</th>
                <th scope="col" className="px-4 py-3">Status</th>
                <th scope="col" className="px-4 py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-12 text-center">
                    <Database className="w-8 h-8 text-slate-600 mx-auto mb-3" />
                    <div className="text-sm font-semibold text-slate-300">No activity records</div>
                    <div className="text-xs text-slate-500 mt-1">
                      {activities.length === 0
                        ? 'Log your first activity data record to begin the carbon accounting flow.'
                        : 'No records match the current filters.'}
                    </div>
                  </td>
                </tr>
              ) : (
                filtered.map((act) => (
                <tr key={act.id} className="hover:bg-slate-800/50 transition">
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
                      <div
                        className="flex items-center gap-1.5 text-emerald-400"
                        title={`SHA-256 ${act.evidence.sha256Hash}`}
                      >
                        <FileText className="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
                        <span className="truncate max-w-[130px] font-mono text-[11px]">{act.evidence.fileName}</span>
                        <span className="sr-only">Evidence attached, SHA-256 {act.evidence.sha256Hash}</span>
                      </div>
                    ) : (
                      <button
                        onClick={() => onUploadEvidenceForActivity(act.id)}
                        aria-label={`Attach evidence bill to activity at ${act.facilityName}, ${act.activityType}`}
                        className="flex items-center gap-1 text-slate-400 hover:text-sky-400 text-[11px] transition"
                      >
                        <Paperclip className="w-3.5 h-3.5" aria-hidden="true" />
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
                      aria-label={`Run calculation for ${act.activityType} at ${act.facilityName}`}
                      className="px-2.5 py-1 bg-slate-800 hover:bg-emerald-600 text-slate-200 hover:text-white rounded text-[11px] font-semibold border border-slate-700 hover:border-emerald-500 transition"
                    >
                      Calculate
                    </button>
                  </td>
                </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Log Activity Modal — Escape closes, focus is trapped and restored. */}
      {isModalOpen && (
        <Modal
          title="Log Operational Activity Data"
          description="Record fuel, utility meter or fleet activity against a reporting facility and period."
          onClose={() => setIsModalOpen(false)}
          closeLabel="Cancel logging activity data"
        >
          <form onSubmit={handleSubmit} className="space-y-3 text-xs">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="activity-facility" className="block text-slate-300 mb-1 font-medium">
                  Facility
                </label>
                <select
                  id="activity-facility"
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
                <label htmlFor="activity-period" className="block text-slate-300 mb-1 font-medium">
                  Reporting Period
                </label>
                <select
                  id="activity-period"
                  value={selectedPeriod}
                  onChange={(e) => handlePeriodChange(e.target.value)}
                  aria-describedby="activity-period-hint"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  {periods.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </select>
                <p id="activity-period-hint" className="mt-1 text-[11px] text-slate-500">
                  Choosing a period sets the start and end dates below.
                </p>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="activity-start-date" className="block text-slate-300 mb-1 font-medium">
                  Start Date
                </label>
                <input
                  id="activity-start-date"
                  required
                  type="date"
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
              <div>
                <label htmlFor="activity-end-date" className="block text-slate-300 mb-1 font-medium">
                  End Date
                </label>
                <input
                  id="activity-end-date"
                  required
                  type="date"
                  value={endDate}
                  onChange={(e) => setEndDate(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                />
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="activity-scope" className="block text-slate-300 mb-1 font-medium">
                  GHG Scope
                </label>
                <select
                  id="activity-scope"
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
                  aria-describedby="activity-scope-hint"
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
                >
                  <option value="SCOPE_1">Scope 1 (Direct)</option>
                  <option value="SCOPE_2">Scope 2 (Electricity)</option>
                </select>
                <p id="activity-scope-hint" className="mt-1 text-[11px] text-slate-500">
                  The scope sets the available categories, activity types and units.
                </p>
              </div>

              <div>
                <label htmlFor="activity-category" className="block text-slate-300 mb-1 font-medium">
                  Activity Category
                </label>
                <select
                  id="activity-category"
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

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="activity-type" className="block text-slate-300 mb-1 font-medium">
                  Activity Type
                </label>
                <select
                  id="activity-type"
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

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                <div>
                  <label htmlFor="activity-quantity" className="block text-slate-300 mb-1 font-medium">
                    Quantity
                  </label>
                  <input
                    id="activity-quantity"
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
                  <label htmlFor="activity-unit" className="block text-slate-300 mb-1 font-medium">
                    Unit
                  </label>
                  <select
                    id="activity-unit"
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
              <label htmlFor="activity-source" className="block text-slate-300 mb-1 font-medium">
                Primary Source Reference
              </label>
              <input
                id="activity-source"
                value={source}
                onChange={(e) => setSource(e.target.value)}
                placeholder="e.g. DTE Energy Meter Statement #88204"
                className="w-full bg-slate-800 border border-slate-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:border-emerald-500"
              />
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
                Save Activity Data
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
};
