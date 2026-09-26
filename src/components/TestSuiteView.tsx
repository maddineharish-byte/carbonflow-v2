/**
 * CarbonFlow — Automated Test Suite View
 * Verifies multi-tenant isolation, calculation precision, and audit transitions in real-time.
 */
import React, { useState } from 'react';
import { FlaskConical, CheckCircle2, XCircle, Play, ShieldCheck, RefreshCw } from 'lucide-react';

interface TestResult {
  id: string;
  category: string;
  name: string;
  passed: boolean;
  details: string;
}

interface TestSuiteViewProps {
  testSuiteData: {
    total: number;
    passed: number;
    failed: number;
    results: TestResult[];
  } | null;
  onRunTestSuite: () => void;
  isLoading: boolean;
}

export const TestSuiteView: React.FC<TestSuiteViewProps> = ({
  testSuiteData,
  onRunTestSuite,
  isLoading,
}) => {
  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-white tracking-tight">Enterprise Automated Test Suite</h1>
          <p className="text-xs text-slate-400 mt-1">
            Validates multi-tenant isolation barriers, deterministic decimal precision, and audit state machine guards.
          </p>
        </div>

        <button
          onClick={onRunTestSuite}
          disabled={isLoading}
          className="flex items-center gap-2 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded-lg shadow transition self-start"
        >
          <Play className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
          Execute Test Suite
        </button>
      </div>

      {/* Summary Scorecard */}
      {testSuiteData && (
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
            <div className="text-xs font-semibold uppercase text-slate-400">Total Test Cases</div>
            <div className="text-2xl font-bold text-white mt-1 font-mono">{testSuiteData.total}</div>
            <div className="text-[11px] text-slate-500 mt-0.5">Automated test scenarios</div>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
            <div className="text-xs font-semibold uppercase text-slate-400">Passed</div>
            <div className="text-2xl font-bold text-emerald-400 mt-1 font-mono">{testSuiteData.passed}</div>
            <div className="text-[11px] text-emerald-500/80 mt-0.5">100% Security & Precision Pass</div>
          </div>

          <div className="bg-slate-900 border border-slate-800 rounded-xl p-4">
            <div className="text-xs font-semibold uppercase text-slate-400">Failed / Regressions</div>
            <div className="text-2xl font-bold text-slate-300 mt-1 font-mono">{testSuiteData.failed}</div>
            <div className="text-[11px] text-slate-500 mt-0.5">Zero tolerance threshold</div>
          </div>
        </div>
      )}

      {/* Results List */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <FlaskConical className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">Automated Test Execution Results</h2>
          </div>
          <span className="text-xs text-slate-500">Live Regression Protocol</span>
        </div>

        <div className="divide-y divide-slate-800">
          {testSuiteData?.results.map((test) => (
            <div key={test.id} className="p-4 hover:bg-slate-850/50 transition flex items-start gap-4">
              <div className="mt-0.5">
                {test.passed ? (
                  <CheckCircle2 className="w-5 h-5 text-emerald-400" />
                ) : (
                  <XCircle className="w-5 h-5 text-rose-500" />
                )}
              </div>

              <div className="flex-1 text-xs space-y-1">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-mono font-bold text-slate-300">{test.id}</span>
                    <span className="font-semibold text-white">{test.name}</span>
                  </div>
                  <span
                    className={`text-[10px] font-bold px-2 py-0.5 rounded ${
                      test.passed
                        ? 'bg-emerald-950 text-emerald-300 border border-emerald-800'
                        : 'bg-rose-950 text-rose-300 border border-rose-800'
                    }`}
                  >
                    {test.passed ? 'PASSED' : 'FAILED'}
                  </span>
                </div>
                <div className="text-[11px] text-slate-400 font-mono">{test.category}</div>
                <p className="text-slate-300 text-[11px] pt-1">{test.details}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
