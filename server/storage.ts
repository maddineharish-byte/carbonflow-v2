/**
 * CarbonFlow — Private Evidence Storage Abstraction
 * Local filesystem adapter for self-contained execution, pluggable for Supabase Storage.
 */
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

export interface StoredFileMetadata {
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
  storagePath: string;
}

export interface StorageService {
  saveFile(organizationId: string, originalName: string, mimeType: string, buffer: Buffer): Promise<StoredFileMetadata>;
  readFile(storagePath: string): Promise<Buffer>;
  deleteFile(storagePath: string): Promise<void>;
}

export const ALLOWED_MIME_TYPES = new Set([
  'application/pdf',
  'text/csv',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', // .xlsx
  'application/vnd.ms-excel', // .xls
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document', // .docx
  'image/png',
  'image/jpeg',
  'image/jpg',
  'text/plain',
]);

export const MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024; // 25 MB

function isPathInside(baseDir: string, candidate: string): boolean {
  const relative = path.relative(baseDir, candidate);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function hasExpectedSignature(mimeType: string, buffer: Buffer): boolean {
  if (mimeType === 'application/pdf') return buffer.subarray(0, 5).toString('ascii') === '%PDF-';
  if (mimeType === 'image/png') return buffer.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  if (mimeType === 'image/jpeg' || mimeType === 'image/jpg') return buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff;
  if (mimeType === 'application/vnd.ms-excel') return buffer.subarray(0, 8).equals(Buffer.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1]));
  if (mimeType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' || mimeType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return buffer.subarray(0, 2).toString('ascii') === 'PK';
  if (mimeType === 'text/plain' || mimeType === 'text/csv') return !buffer.subarray(0, 4096).includes(0);
  return false;
}

export function assertFileSignature(mimeType: string, buffer: Buffer): void {
  if (!hasExpectedSignature(mimeType, buffer)) {
    throw new Error(`File content does not match declared MIME type '${mimeType}'.`);
  }
}

export class LocalStorageAdapter implements StorageService {
  private baseDir: string;

  constructor() {
    this.baseDir = path.resolve(process.cwd(), 'vault_storage');
    if (!fs.existsSync(this.baseDir)) {
      fs.mkdirSync(this.baseDir, { recursive: true });
    }
  }

  async saveFile(organizationId: string, originalName: string, mimeType: string, buffer: Buffer): Promise<StoredFileMetadata> {
    if (buffer.length > MAX_FILE_SIZE_BYTES) {
      throw new Error(`File size ${buffer.length} exceeds 25 MB limit.`);
    }

    if (!ALLOWED_MIME_TYPES.has(mimeType)) {
      throw new Error(`MIME type '${mimeType}' is not supported for evidence documents.`);
    }
    assertFileSignature(mimeType, buffer);

    const tenantDir = path.join(this.baseDir, organizationId);
    if (!fs.existsSync(tenantDir)) {
      fs.mkdirSync(tenantDir, { recursive: true });
    }

    const sha256Hash = crypto.createHash('sha256').update(buffer).digest('hex');
    const safeStorageFileName = `${Date.now()}_${crypto.randomUUID().slice(0, 8)}_${path.basename(originalName).replace(/[^a-zA-Z0-9._-]/g, '_')}`;
    const storagePath = path.join(tenantDir, safeStorageFileName);

    await fs.promises.writeFile(storagePath, buffer);

    return {
      fileName: originalName,
      fileSizeBytes: buffer.length,
      mimeType,
      sha256Hash,
      storagePath,
    };
  }

  async readFile(storagePath: string): Promise<Buffer> {
    const normalized = path.resolve(storagePath);
    if (!isPathInside(this.baseDir, normalized)) {
      throw new Error('Access denied: path traversal attempt.');
    }
    if (!fs.existsSync(normalized)) {
      throw new Error('Evidence file not found on disk.');
    }
    return fs.promises.readFile(normalized);
  }

  async deleteFile(storagePath: string): Promise<void> {
    const normalized = path.resolve(storagePath);
    if (isPathInside(this.baseDir, normalized) && fs.existsSync(normalized)) {
      await fs.promises.unlink(normalized);
    }
  }
}

// Export singleton instance
export const storageService: StorageService = new LocalStorageAdapter();
