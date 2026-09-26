// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';

const { Pool } = pg;
let pool: any;

function getPool(): any {
  if (pool) return pool;
  if (!process.env.DB_PASSWORD) throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
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

export interface FacilityRecord {
  id: string;
  organizationId: string;
  legalEntityId?: string;
  name: string;
  facilityCode: string;
  facilityType: string;
  country: string;
  stateProvince?: string;
  gridRegion?: string;
  floorAreaM2?: number;
  createdAt: string;
}

export interface LegalEntityRecord {
  id: string;
  organizationId: string;
  name: string;
  jurisdiction: string;
  registrationNumber?: string;
  ownershipPercentage: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReportingPeriodRecord {
  id: string;
  organizationId: string;
  name: string;
  startDate: string;
  endDate: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

function persistenceError(error: unknown): never {
  if (error && typeof error === 'object' && 'code' in error && error.code === '23505') {
    throw new Error('DUPLICATE_SCOPE_RECORD');
  }
  if (error && typeof error === 'object' && 'code' in error && error.code === '23503') {
    throw new Error('INVALID_SCOPE_RELATIONSHIP');
  }
  throw error;
}

export async function listFacilities(organizationId: string): Promise<FacilityRecord[]> {
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, legal_entity_id::text, name, facility_code,
            facility_type, country, state_province, grid_region, floor_area_m2::text, created_at::text
       FROM facilities WHERE organization_id = $1 ORDER BY name`,
    [organizationId],
  );
  return result.rows.map((row: any) => ({
    id: String(row.id), organizationId: String(row.organization_id), legalEntityId: row.legal_entity_id ? String(row.legal_entity_id) : undefined,
    name: String(row.name), facilityCode: String(row.facility_code), facilityType: String(row.facility_type), country: String(row.country),
    stateProvince: row.state_province ? String(row.state_province) : undefined, gridRegion: row.grid_region ? String(row.grid_region) : undefined,
    floorAreaM2: row.floor_area_m2 === null ? undefined : Number(row.floor_area_m2), createdAt: String(row.created_at),
  }));
}

export async function createFacility(organizationId: string, input: Record<string, unknown>): Promise<FacilityRecord> {
  if (!input.name || !input.facilityCode || !input.country || !input.gridRegion) throw new Error('VALIDATION_ERROR');
  const floorArea = input.floorAreaM2 === undefined ? null : Number(input.floorAreaM2);
  if (floorArea !== null && (!Number.isFinite(floorArea) || floorArea < 0)) throw new Error('VALIDATION_ERROR');
  const facilityType = String(input.facilityType || 'MANUFESTURING');
  if (!['MANUFACTURING', 'OFFICE', 'DATA_CENTER', 'WAREHOUSE', 'RETAIL', 'LOGISTICS'].includes(facilityType)) throw new Error('VALIDATION_ERROR');
  try {
    const result = await getPool().query(
      `INSERT INTO facilities (organization_id, legal_entity_id, name, facility_code, facility_type, country, state_province, grid_region, floor_area_m2)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       RETURNING id::text, organization_id::text, legal_entity_id::text, name, facility_code, facility_type, country, state_province, grid_region, floor_area_m2::text, created_at::text`,
      [organizationId, input.legalEntityId || null, String(input.name).trim(), String(input.facilityCode).trim(), facilityType, String(input.country).trim(), input.stateProvince || null, String(input.gridRegion).trim(), floorArea],
    );
    const row = result.rows[0];
    return { id: String(row.id), organizationId: String(row.organization_id), legalEntityId: row.legal_entity_id || undefined, name: String(row.name), facilityCode: String(row.facility_code), facilityType: String(row.facility_type), country: String(row.country), stateProvince: row.state_province || undefined, gridRegion: row.grid_region || undefined, floorAreaM2: row.floor_area_m2 === null ? undefined : Number(row.floor_area_m2), createdAt: String(row.created_at) };
  } catch (error) {
    return persistenceError(error);
  }
}

export async function listLegalEntities(organizationId: string): Promise<LegalEntityRecord[]> {
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, name, jurisdiction, registration_number, ownership_percentage::text, created_at::text, updated_at::text
       FROM legal_entities WHERE organization_id = $1 ORDER BY name`, [organizationId],
  );
  return result.rows.map((row: any) => ({ id: String(row.id), organizationId: String(row.organization_id), name: String(row.name), jurisdiction: String(row.jurisdiction), registrationNumber: row.registration_number || undefined, ownershipPercentage: Number(row.ownership_percentage), createdAt: String(row.created_at), updatedAt: String(row.updated_at) }));
}

export async function listReportingPeriods(organizationId: string): Promise<ReportingPeriodRecord[]> {
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, name, start_date::text, end_date::text, status, created_at::text, updated_at::text
       FROM reporting_periods WHERE organization_id = $1 ORDER BY start_date DESC`, [organizationId],
  );
  return result.rows.map((row: any) => ({ id: String(row.id), organizationId: String(row.organization_id), name: String(row.name), startDate: String(row.start_date), endDate: String(row.end_date), status: String(row.status), createdAt: String(row.created_at), updatedAt: String(row.updated_at) }));
}

export async function createReportingPeriod(organizationId: string, input: Record<string, unknown>): Promise<ReportingPeriodRecord> {
  if (!input.name || !input.startDate || !input.endDate) throw new Error('VALIDATION_ERROR');
  const start = new Date(String(input.startDate));
  const end = new Date(String(input.endDate));
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || end < start) throw new Error('VALIDATION_ERROR');
  try {
    const result = await getPool().query(
      `INSERT INTO reporting_periods (organization_id, name, start_date, end_date, status)
       VALUES ($1, $2, $3, $4, $5)
       RETURNING id::text, organization_id::text, name, start_date::text, end_date::text, status, created_at::text, updated_at::text`,
      [organizationId, String(input.name).trim(), start.toISOString().slice(0, 10), end.toISOString().slice(0, 10), String(input.status || 'OPEN')],
    );
    const row = result.rows[0];
    return { id: String(row.id), organizationId: String(row.organization_id), name: String(row.name), startDate: String(row.start_date), endDate: String(row.end_date), status: String(row.status), createdAt: String(row.created_at), updatedAt: String(row.updated_at) };
  } catch (error) {
    return persistenceError(error);
  }
}

export async function closeScopePersistence(): Promise<void> {
  if (pool) { const current = pool; pool = undefined; await current.end(); }
}
