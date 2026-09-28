/**
 * CarbonFlow — Private Evidence Management Vault
 * Multi-tenant storage with SHA-256 checksum tracking (Phase 8: the checksum
 * is a deterministic integrity digest, not encryption).
 */
import React, { useState, useRef } from 'react';
import { FolderLock, UploadCloud, FileText, Download, ShieldCheck, HardDrive, AlertTriangle } from 'lucide-react';
import { EvidenceRecord } from '../types.ts';

interface EvidenceViewProps {
  evidence: EvidenceRecord[];
  linkedActivityId?: string | null;
  onUpload: (file: File) => Promise<void>;
  onDownload: (evidenceId: string, fileName: string) => Promise<void>;
}

export const EvidenceView: React.FC<EvidenceViewProps> = ({ evidence, linkedActivityId, onUpload, onDownload }) => {
  const [isUploading, setIsUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const file = files[0];
    if (file.size > 25 * 1024 * 1024) {
      setUploadError('File size exceeds the 25 MB limit.');
      return;
    }

    try {
      setIsUploading(true);
      setUploadError(null);
      await onUpload(file);
    } catch (err: any) {
      setUploadError(err.message || 'Evidence upload failed.');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDownload = async (evidenceId: string, fileName: string) => {
    try {
      setDownloadingId(evidenceId);
      setDownloadError(null);
      await onDownload(evidenceId, fileName);
    } catch (err: any) {
      setDownloadError(err.message || 'Evidence download failed.');
    } finally {
      setDownloadingId(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-white tracking-tight">Evidence Management Vault</h1>
        <p className="text-xs text-slate-400 mt-1">
          Hashed primary utility bills, meter invoices, and contractual PPA guarantee documents.
        </p>
      </div>

      {uploadError && (
        <div className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          {uploadError}
        </div>
      )}
      {downloadError && (
        <div className="flex items-center gap-2 px-4 py-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-xs text-rose-200">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          {downloadError}
        </div>
      )}

      {/* Upload Dropzone */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragActive(true);
        }}
        onDragLeave={() => setDragActive(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragActive(false);
          handleFiles(e.dataTransfer.files);
        }}
        className={`border-2 border-dashed rounded-xl p-8 text-center transition ${
          dragActive
            ? 'border-emerald-500 bg-emerald-950/20'
            : 'border-slate-800 bg-slate-900/60 hover:border-slate-700'
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          className="hidden"
          accept=".pdf,.csv,.xlsx,.xls,.docx,.png,.jpg,.jpeg,.txt"
          onChange={(e) => handleFiles(e.target.files)}
        />
        <div className="flex flex-col items-center justify-center gap-3">
          <div className="w-12 h-12 rounded-xl bg-slate-800 flex items-center justify-center text-emerald-400 border border-slate-700">
            <UploadCloud className="w-6 h-6" />
          </div>
          <div>
            <div className="text-sm font-semibold text-white">Upload Primary Evidence Document</div>
            <div className="text-xs text-slate-400 mt-0.5">
              Drag & drop files or{' '}
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="text-emerald-400 hover:underline font-semibold"
              >
                browse local files
              </button>
            </div>
          </div>
          <div className="text-[11px] text-slate-500">
            Supported formats: PDF, CSV, XLSX, DOCX, PNG (Max 25 MB). Computes immediate SHA-256 checksum.
          </div>
        </div>
      </div>

      {/* Vault Files Table */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
        <div className="p-4 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <HardDrive className="w-4 h-4 text-emerald-400" />
            <h2 className="text-sm font-bold text-white">
            Vault Repository ({evidence.length} files){linkedActivityId ? ' — upload will attach to selected activity' : ''}
          </h2>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <ShieldCheck className="w-4 h-4 text-emerald-400" />
            <span>SHA-256 Checksums Logged</span>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs text-slate-300">
            <thead className="bg-slate-800 text-slate-400 uppercase text-[10px] tracking-wider border-b border-slate-800">
              <tr>
                <th className="px-4 py-3">Document Name</th>
                <th className="px-4 py-3">File Size</th>
                <th className="px-4 py-3">MIME Type</th>
                <th className="px-4 py-3">SHA-256 Checksum</th>
                <th className="px-4 py-3">Uploaded</th>
                <th className="px-4 py-3 text-right">Download</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800">
              {evidence.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center">
                    <FolderLock className="w-8 h-8 text-slate-600 mx-auto mb-3" />
                    <div className="text-sm font-semibold text-slate-300">No evidence uploaded yet</div>
                    <div className="text-xs text-slate-500 mt-1">
                      Upload primary source documents to attach them to activities or audits.
                    </div>
                  </td>
                </tr>
              ) : (
                evidence.map((file) => (
                  <tr key={file.id} className="hover:bg-slate-800/50 transition">
                    <td className="px-4 py-3 font-semibold text-white flex items-center gap-2">
                      <FileText className="w-4 h-4 text-sky-400 shrink-0" />
                      <span>{file.fileName}</span>
                    </td>
                    <td className="px-4 py-3 text-slate-400">
                      {(file.fileSizeBytes / (1024 * 1024)).toFixed(2)} MB
                    </td>
                    <td className="px-4 py-3 font-mono text-[11px] text-slate-400">{file.mimeType}</td>
                    <td className="px-4 py-3 font-mono text-[10px] text-emerald-400/90 break-all max-w-[200px]">
                      {file.sha256Hash}
                    </td>
                    <td className="px-4 py-3 text-slate-400 text-[11px]">
                      {new Date(file.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button
                        type="button"
                        onClick={() => handleDownload(file.id, file.fileName)}
                        disabled={downloadingId === file.id}
                        className="inline-flex items-center gap-1 px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded text-[11px] font-medium transition disabled:opacity-50"
                      >
                        <Download className="w-3.5 h-3.5 text-emerald-400" />
                        {downloadingId === file.id ? 'Loading…' : 'Get'}
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
