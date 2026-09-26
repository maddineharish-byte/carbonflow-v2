// @ts-expect-error pg has no bundled TypeScript declarations
import pg from 'pg';
import type { ActivityData } from './types.ts';

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
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const SUPPORTED_SCOPES = new Set<ActivityData['scope']>(['SCOPE_1', 'SCOPE_2', 'SCOPE_3']);
const SUPPORTED_STATUSES = new Set<ActivityData['status']>(['DRAFT', 'SUBMITTED', 'VALIDATED', 'CALCULATED', 'LOCKED']);

export interface ActivityEvidence {
  id: string;
  fileName: string;
  fileSizeBytes: number;
  mimeType: string;
  sha256Hash: string;
}

export interface ActivityRecord extends ActivityData {
  facilityName?: string;
  calculation: null;
  evidence: ActivityEvidence | null;
}

function assertUuid(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new Error(field.endsWith('Id') ? 'VALIDATION_ERROR' : 'VALIDATION_ERROR');
  }
}

function parseDate(value: unknown, field: string): string {
  if (typeof value !== 'string' || !DATE_PATTERN.test(value)) throw new Error('VALIDATION_ERROR');
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day ||
    !Number.isFinite(date.getTime())
  ) {
    throw new Error('VALIDATION_ERROR');
  }
  return value;
}

function requireText(value: unknown, field: string, maximumLength: number): string {
  if (typeof value !== 'string' || !value.trim() || value.trim().length > maximumLength) {
    throw new Error('VALIDATION_ERROR');
  }
  return value.trim();
}

function mapEvidence(row: any): ActivityEvidence | null {
  if (!row?.evidence_id) return null;
  return {
    id: String(row.evidence_id),
    fileName: String(row.evidence_file_name),
    fileSizeBytes: Number(row.evidence_file_size_bytes),
    mimeType: String(row.evidence_mime_type),
    sha256Hash: String(row.evidence_sha256_hash),
  };
}

function mapActivity(row: any): ActivityRecord {
  return {
    id: String(row.id),
    organizationId: String(row.organization_id),
    reportingPeriodId: String(row.reporting_period_id),
    facilityId: String(row.facility_id),
    departmentId: row.department_id ? String(row.department_id) : undefined,
    scope: String(row.scope) as ActivityData['scope'],
    category: String(row.category),
    activityType: String(row.activity_type),
    quantity: Number(row.quantity),
    unit: String(row.unit),
    startDate: String(row.start_date),
    endDate: String(row.end_date),
    source: String(row.source),
    status: String(row.status) as ActivityData['status'],
    notes: row.notes ? String(row.notes) : undefined,
    submittedBy: row.submitted_by ? String(row.submitted_by) : undefined,
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
    facilityName: row.facility_name ? String(row.facility_name) : undefined,
    calculation: null,
    evidence: mapEvidence(row),
  };
}

const ACTIVITY_SELECT = `
  SELECT a.id::text, a.organization_id::text, a.reporting_period_id::text, a.facility_id::text,
         a.department_id::text, a.scope, a.category, a.activity_type, a.quantity::text,
         a.unit, a.start_date::text, a.end_date::text, a.source, a.status, a.notes,
         a.submitted_by::text, a.created_at::text, a.updated_at::text, f.name AS facility_name,
         ev.id::text AS evidence_id, ev.file_name AS evidence_file_name,
         ev.file_size_bytes::text AS evidence_file_size_bytes,
         ev.mime_type AS evidence_mime_type, ev.sha256_hash AS evidence_sha256_hash
    FROM activity_data a
    JOIN facilities f
      ON f.id = a.facility_id AND f.organization_id = a.organization_id
    LEFT JOIN LATERAL (
      SELECT er.id, er.file_name, er.file_size_bytes, er.mime_type, er.sha256_hash
        FROM evidence_links el
        JOIN evidence_records er
          ON er.id = el.evidence_record_id AND er.organization_id = a.organization_id
       WHERE el.entity_type = 'ACTIVITY_DATA' AND el.entity_id = a.id
       ORDER BY el.created_at, er.created_at
       LIMIT 1
    ) ev ON TRUE`;

function validateFilterUuid(value: unknown): string | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  assertUuid(value, 'filter');
  return value;
}

export async function listActivity(
  organizationId: string,
  filters: { periodId?: string; facilityId?: string; scope?: string } = {},
): Promise<ActivityRecord[]> {
  assertUuid(organizationId, 'organizationId');
  const periodId = validateFilterUuid(filters.periodId);
  const facilityId = validateFilterUuid(filters.facilityId);
  if (filters.scope !== undefined && !SUPPORTED_SCOPES.has(filters.scope as ActivityData['scope'])) {
    throw new Error('VALIDATION_ERROR');
  }

  const result = await getPool().query(
    `${ACTIVITY_SELECT}
      WHERE a.organization_id = $1
        AND ($2::uuid IS NULL OR a.reporting_period_id = $2)
        AND ($3::uuid IS NULL OR a.facility_id = $3)
        AND ($4::text IS NULL OR a.scope = $4)
      ORDER BY a.start_date DESC, a.created_at DESC`,
    [organizationId, periodId || null, facilityId || null, filters.scope || null],
  );
  return result.rows.map(mapActivity);
}

export async function getActivity(organizationId: string, activityId: string): Promise<ActivityRecord | undefined> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(activityId, 'activityId');
  const result = await getPool().query(
    `${ACTIVITY_SELECT} WHERE a.organization_id = $1 AND a.id = $2`,
    [organizationId, activityId],
  );
  return result.rows[0] ? mapActivity(result.rows[0]) : undefined;
}

export async function createActivity(
  organizationId: string,
  userId: string,
  input: Record<string, unknown>,
): Promise<ActivityRecord> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(userId, 'userId');
  const reportingPeriodId = input.reportingPeriodId;
  const facilityId = input.facilityId;
  assertUuid(reportingPeriodId, 'reportingPeriodId');
  assertUuid(facilityId, 'facilityId');

  const scope = String(input.scope || '') as ActivityData['scope'];
  if (!SUPPORTED_SCOPES.has(scope)) throw new Error('VALIDATION_ERROR');
  if (input.status !== undefined && input.status !== 'SUBMITTED') throw new Error('VALIDATION_ERROR');
  const status: ActivityData['status'] = 'SUBMITTED';
  const category = requireText(input.category, 'category', 100);
  const activityType = requireText(input.activityType, 'activityType', 100);
  const unit = requireText(input.unit, 'unit', 50);
  const source = requireText(input.source, 'source', 150);
  const quantity = typeof input.quantity === 'number' ? input.quantity : Number(input.quantity);
  if (!Number.isFinite(quantity) || quantity < 0) throw new Error('VALIDATION_ERROR');
  const startDate = parseDate(input.startDate, 'startDate');
  const endDate = parseDate(input.endDate, 'endDate');
  if (endDate < startDate) throw new Error('VALIDATION_ERROR');
  const notes = input.notes === undefined || input.notes === null || input.notes === ''
    ? null
    : requireText(input.notes, 'notes', 10000);

  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const uploader = await client.query(
      `SELECT 1 FROM organization_memberships
        WHERE organization_id = $1 AND user_id = $2 AND is_active = TRUE
          AND EXISTS (SELECT 1 FROM users WHERE id = user_id AND is_active = TRUE)`,
      [organizationId, userId],
    );
    const facility = await client.query(
      'SELECT id, name FROM facilities WHERE id = $1 AND organization_id = $2 FOR SHARE',
      [facilityId, organizationId],
    );
    const period = await client.query(
      'SELECT 1 FROM reporting_periods WHERE id = $1 AND organization_id = $2 FOR SHARE',
      [reportingPeriodId, organizationId],
    );
    if (!uploader.rows[0] || !facility.rows[0] || !period.rows[0]) {
      throw new Error('INVALID_ACTIVITY_RELATIONSHIP');
    }

    const result = await client.query(
      `INSERT INTO activity_data
         (organization_id, reporting_period_id, facility_id, scope, category, activity_type,
          quantity, unit, start_date, end_date, source, status, notes, submitted_by)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
       RETURNING id::text, organization_id::text, reporting_period_id::text, facility_id::text,
                 department_id::text, scope, category, activity_type, quantity::text, unit,
                 start_date::text, end_date::text, source, status, notes, submitted_by::text,
                 created_at::text, updated_at::text`,
      [
        organizationId, reportingPeriodId, facilityId, scope, category, activityType,
        quantity, unit, startDate, endDate, source, status, notes, userId,
      ],
    );
    await client.query('COMMIT');
    return mapActivity({ ...result.rows[0], facility_name: facility.rows[0].name });
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch { /* connection closed */ }
    if (error instanceof Error && error.message === 'INVALID_ACTIVITY_RELATIONSHIP') throw error;
    if (error && typeof error === 'object' && 'code' in error && error.code === '23503') {
      throw new Error('INVALID_ACTIVITY_RELATIONSHIP');
    }
    throw error;
  } finally {
    client.release();
  }
}

export async function updateActivityStatus(
  organizationId: string,
  activityId: string,
  status: ActivityData['status'],
): Promise<boolean> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(activityId, 'activityId');
  if (!SUPPORTED_STATUSES.has(status)) throw new Error('VALIDATION_ERROR');
  const result = await getPool().query(
    `UPDATE activity_data
        SET status = $1, updated_at = CURRENT_TIMESTAMP
      WHERE organization_id = $2 AND id = $3
      RETURNING id::text`,
    [status, organizationId, activityId],
  );
  return result.rowCount === 1;
}

export const listActivityData = listActivity;
export const createActivityData = createActivity;
export const findActivityData = getActivity;

export async function closeActivityPersistence(): Promise<void> {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}
