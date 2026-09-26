// @ts-expect-error pg has no bundled TypeScript declarations
import pg from 'pg';

const { Pool } = pg;
let pool: any;

function getPool(): any {
  if (pool) return pool;
  if (!process.env.DB_PASSWORD) {
    throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
  }
  pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    database: process.env.DB_NAME || 'carbonflow_dev',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD,
    connectionTimeoutMillis: 5000,
  });
  return pool;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SUPPORTED_LINK_TYPES = ['ACTIVITY_DATA', 'AUDIT', 'FACILITY'] as const;
type EvidenceLinkType = typeof SUPPORTED_LINK_TYPES[number];

export interface EvidenceLinkRecord {
  entityType: EvidenceLinkType;
  entityId: string;
}

export interface EvidenceRecord {
  id: string;
  organizationId: string;
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
  storagePath: string;
  uploadedBy?: string;
  createdAt: string;
  links?: EvidenceLinkRecord[];
}

function assertUuid(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) throw new Error('VALIDATION_ERROR');
}

function mapEvidence(row: any): EvidenceRecord {
  const record: EvidenceRecord = {
    id: String(row.id),
    organizationId: String(row.organization_id),
    fileName: String(row.file_name),
    fileSizeBytes: Number(row.file_size_bytes),
    mimeType: String(row.mime_type),
    sha256Hash: String(row.sha256_hash),
    storagePath: String(row.storage_path),
    uploadedBy: row.uploaded_by ? String(row.uploaded_by) : undefined,
    createdAt: String(row.created_at),
  };
  if (row.links !== undefined) {
    record.links = Array.isArray(row.links) ? row.links : [];
  }
  return record;
}

function validateInput(input: {
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
  storagePath: string;
}): void {
  if (
    typeof input.fileName !== 'string' || !input.fileName.trim() || input.fileName.length > 255 ||
    !Number.isInteger(input.fileSizeBytes) || input.fileSizeBytes < 0 || input.fileSizeBytes > 25 * 1024 * 1024 ||
    typeof input.mimeType !== 'string' || !input.mimeType.trim() || input.mimeType.length > 100 ||
    typeof input.sha256Hash !== 'string' || !/^[a-f0-9]{64}$/i.test(input.sha256Hash) ||
    typeof input.storagePath !== 'string' || !input.storagePath.trim() || input.storagePath.length > 500
  ) {
    throw new Error('VALIDATION_ERROR');
  }
}

export async function listEvidence(organizationId: string): Promise<EvidenceRecord[]> {
  assertUuid(organizationId);
  const result = await getPool().query(
    `SELECT e.id::text, e.organization_id::text, e.file_name, e.file_size_bytes::text,
            e.mime_type, e.sha256_hash, e.storage_path, e.uploaded_by::text, e.created_at::text,
            COALESCE(
              json_agg(json_build_object('entityType', l.entity_type, 'entityId', l.entity_id::text))
                FILTER (WHERE l.id IS NOT NULL),
              '[]'::json
            ) AS links
       FROM evidence_records e
       LEFT JOIN evidence_links l ON l.evidence_record_id = e.id
      WHERE e.organization_id = $1
      GROUP BY e.id
      ORDER BY e.created_at DESC, e.id`,
    [organizationId],
  );
  return result.rows.map(mapEvidence);
}

export async function getEvidence(organizationId: string, evidenceId: string): Promise<EvidenceRecord | undefined> {
  assertUuid(organizationId);
  assertUuid(evidenceId);
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, file_name, file_size_bytes::text, mime_type,
            sha256_hash, storage_path, uploaded_by::text, created_at::text
       FROM evidence_records
      WHERE organization_id = $1 AND id = $2`,
    [organizationId, evidenceId],
  );
  return result.rows[0] ? mapEvidence(result.rows[0]) : undefined;
}

export async function createEvidence(
  organizationId: string,
  userId: string,
  input: { fileName: string; fileSizeBytes: number; mimeType: string; sha256Hash: string; storagePath: string },
  link?: { entityType?: string; entityId?: string },
): Promise<EvidenceRecord> {
  assertUuid(organizationId);
  assertUuid(userId);
  validateInput(input);

  if (Boolean(link?.entityType) !== Boolean(link?.entityId)) throw new Error('VALIDATION_ERROR');
  if (link?.entityType && !SUPPORTED_LINK_TYPES.includes(link.entityType as EvidenceLinkType)) {
    throw new Error('INVALID_EVIDENCE_LINK');
  }
  if (link?.entityId) assertUuid(link.entityId);

  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const uploader = await client.query(
      `SELECT 1
         FROM organization_memberships m
         JOIN users u ON u.id = m.user_id
        WHERE m.organization_id = $1 AND m.user_id = $2
          AND m.is_active = TRUE AND u.is_active = TRUE
        FOR SHARE OF m, u`,
      [organizationId, userId],
    );
    if (!uploader.rows[0]) throw new Error('USER_DEACTIVATED');

    const result = await client.query(
      `INSERT INTO evidence_records
         (organization_id, file_name, file_size_bytes, mime_type, sha256_hash, storage_path, uploaded_by)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       RETURNING id::text, organization_id::text, file_name, file_size_bytes::text, mime_type,
                 sha256_hash, storage_path, uploaded_by::text, created_at::text`,
      [organizationId, input.fileName, input.fileSizeBytes, input.mimeType, input.sha256Hash, input.storagePath, userId],
    );
    const record = result.rows[0];

    if (link?.entityType && link.entityId) {
      const tableByType: Record<EvidenceLinkType, string> = {
        ACTIVITY_DATA: 'activity_data',
        AUDIT: 'carbon_audits',
        FACILITY: 'facilities',
      };
      const table = tableByType[link.entityType as EvidenceLinkType];
      // The table is selected only from the fixed allow-list above; entity values remain parameterized.
      const entity = await client.query(
        `SELECT id FROM ${table} WHERE id = $1 AND organization_id = $2 FOR SHARE`,
        [link.entityId, organizationId],
      );
      if (!entity.rows[0]) throw new Error('INVALID_EVIDENCE_LINK');
      await client.query(
        `INSERT INTO evidence_links (evidence_record_id, entity_type, entity_id)
         VALUES ($1,$2,$3)`,
        [record.id, link.entityType, link.entityId],
      );
    }

    await client.query('COMMIT');
    return mapEvidence(record);
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch { /* connection closed */ }
    throw error;
  } finally {
    client.release();
  }
}

export const findEvidence = getEvidence;

export async function closeEvidencePersistence(): Promise<void> {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}
