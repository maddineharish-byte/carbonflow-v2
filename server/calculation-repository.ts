// @ts-expect-error pg has no bundled TypeScript declarations
import pg from 'pg';
import type {
  ActivityData,
  Calculation,
  CalculationGasResult,
  EmissionFactorVersion,
  EmissionRecord,
  GwpSet,
  GwpValue,
} from './types.ts';

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
const SUPPORTED_SCOPES = new Set(['SCOPE_1', 'SCOPE_2', 'SCOPE_3']);

export interface CalculationReferenceResult {
  factorVersion: EmissionFactorVersion;
  factorInputUnit: string;
  gwpSet: GwpSet;
  gwpValues: GwpValue[];
}

function assertUuid(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) throw new Error('VALIDATION_ERROR');
}

function mapFactorVersion(row: any): EmissionFactorVersion {
  return {
    id: String(row.id),
    emissionFactorId: String(row.emission_factor_id),
    versionNumber: Number(row.version_number),
    co2Factor: Number(row.co2_factor),
    ch4Factor: Number(row.ch4_factor),
    n2oFactor: Number(row.n2o_factor),
    co2eFactor: Number(row.co2e_factor),
    factorUnit: String(row.factor_unit),
    source: String(row.source),
    sourceYear: Number(row.source_year),
    geography: String(row.geography),
    status: String(row.status) as EmissionFactorVersion['status'],
    effectiveStart: String(row.effective_start),
    effectiveEnd: row.effective_end ? String(row.effective_end) : undefined,
  };
}

function mapGwpSet(row: any): GwpSet {
  return {
    id: String(row.id),
    code: String(row.code),
    name: String(row.name),
    assessmentReport: String(row.assessment_report),
    publicationYear: Number(row.publication_year),
    isDefault: Boolean(row.is_default),
  };
}

function mapGwpValue(row: any): GwpValue {
  return {
    id: String(row.id),
    gwpSetId: String(row.gwp_set_id),
    gas: String(row.gas),
    gwp100yr: Number(row.gwp_100yr),
  };
}

export async function listGwpSetsWithValues(): Promise<Array<GwpSet & { values: GwpValue[] }>> {
  const sets = await getPool().query(
    `SELECT id::text, code, name, assessment_report, publication_year, is_default
       FROM gwp_sets ORDER BY is_default DESC, publication_year DESC, code`,
  );
  const mappedSets = sets.rows.map(mapGwpSet);
  if (!mappedSets.length) return [];
  const values = await getPool().query(
    `SELECT id::text, gwp_set_id::text, gas, gwp_100yr::text
       FROM gwp_values
      WHERE gwp_set_id = ANY($1::uuid[])
      ORDER BY gas`,
    [mappedSets.map((set: GwpSet) => set.id)],
  );
  const valuesBySet = new Map<string, GwpValue[]>();
  for (const row of values.rows) {
    const value = mapGwpValue(row);
    const list = valuesBySet.get(value.gwpSetId) || [];
    list.push(value);
    valuesBySet.set(value.gwpSetId, list);
  }
  return mappedSets.map((set: GwpSet) => ({ ...set, values: valuesBySet.get(set.id) || [] }));
}

export async function listEmissionFactorsWithVersions() {
  const factors = await getPool().query(
    `SELECT id::text, scope, category, activity_type, fuel_or_activity, input_unit
       FROM emission_factors ORDER BY activity_type, fuel_or_activity`,
  );
  const versions = await getPool().query(
    `SELECT id::text, emission_factor_id::text, version_number, co2_factor::text,
            ch4_factor::text, n2o_factor::text, co2e_factor::text, factor_unit, source,
            source_year, geography, status, effective_start::text, effective_end::text
       FROM emission_factor_versions
      WHERE emission_factor_id = ANY($1::uuid[])
      ORDER BY emission_factor_id, version_number`,
    [factors.rows.map((row: any) => row.id)],
  );
  const versionsByFactor = new Map<string, any[]>();
  for (const row of versions.rows) {
    const version = mapFactorVersion(row);
    const list = versionsByFactor.get(version.emissionFactorId) || [];
    list.push(version);
    versionsByFactor.set(version.emissionFactorId, list);
  }
  return factors.rows.map((row: any) => ({
    id: String(row.id),
    scope: String(row.scope),
    category: String(row.category),
    activityType: String(row.activity_type),
    fuelOrActivity: String(row.fuel_or_activity),
    inputUnit: String(row.input_unit),
    versions: versionsByFactor.get(String(row.id)) || [],
  }));
}

function mapGasResults(value: unknown): CalculationGasResult[] {
  if (!Array.isArray(value)) return [];
  return value.map((row: any) => ({
    gas: String(row.gas),
    rawGasEmissionKg: Number(row.raw_gas_emission_kg),
    gwpApplied: Number(row.gwp_applied),
    co2eKg: Number(row.co2e_kg),
  }));
}

function mapCalculation(row: any): Calculation {
  return {
    id: String(row.id),
    organizationId: String(row.organization_id),
    activityDataId: String(row.activity_data_id),
    reportingPeriodId: String(row.reporting_period_id),
    factorVersionId: String(row.factor_version_id),
    gwpSetId: String(row.gwp_set_id),
    originalQuantity: Number(row.original_quantity),
    originalUnit: String(row.original_unit),
    normalizedQuantity: Number(row.normalized_quantity),
    normalizedUnit: String(row.normalized_unit),
    conversionFactor: Number(row.conversion_factor),
    factorValue: Number(row.factor_value),
    factorUnit: String(row.factor_unit),
    factorSource: String(row.factor_source),
    factorVersion: Number(row.factor_version_number),
    gwpName: String(row.gwp_name),
    totalCo2eKg: Number(row.total_co2e_kg),
    totalCo2eTonnes: Number(row.total_co2e_tonnes),
    calculationHash: String(row.calculation_hash),
    gasResults: mapGasResults(row.gas_results),
    calculatedAt: String(row.calculated_at),
    calculatedBy: row.calculated_by ? String(row.calculated_by) : undefined,
  };
}

function mapEmission(row: any): EmissionRecord {
  return {
    id: String(row.id),
    organizationId: String(row.organization_id),
    reportingPeriodId: String(row.reporting_period_id),
    facilityId: String(row.facility_id),
    calculationId: String(row.calculation_id),
    scope: String(row.scope) as EmissionRecord['scope'],
    category: String(row.category),
    scope2Type: row.scope2_type ? String(row.scope2_type) as EmissionRecord['scope2Type'] : undefined,
    co2eTonnes: Number(row.co2e_tonnes),
    status: String(row.status) as EmissionRecord['status'],
    createdAt: String(row.created_at),
  };
}

const CALCULATION_SELECT = `
  SELECT c.id::text, c.organization_id::text, c.activity_data_id::text,
         c.reporting_period_id::text, c.factor_version_id::text, c.gwp_set_id::text,
         c.original_quantity::text, c.original_unit, c.normalized_quantity::text,
         c.normalized_unit, c.conversion_factor::text, c.factor_value::text,
         c.factor_unit, c.factor_source, c.factor_version_number, c.gwp_name,
         c.total_co2e_kg::text, c.total_co2e_tonnes::text, c.calculation_hash,
         c.calculated_at::text, c.calculated_by::text,
         COALESCE((
           SELECT json_agg(json_build_object(
             'gas', gr.gas,
             'raw_gas_emission_kg', gr.raw_gas_emission_kg::text,
             'gwp_applied', gr.gwp_applied::text,
             'co2e_kg', gr.co2e_kg::text
           ) ORDER BY gr.gas)
           FROM calculation_gas_results gr
           WHERE gr.calculation_id = c.id
         ), '[]'::json) AS gas_results
    FROM calculations c`;

function validateScopeFilter(scope?: string): void {
  if (scope !== undefined && scope !== null && scope !== '' && !SUPPORTED_SCOPES.has(scope)) {
    throw new Error('VALIDATION_ERROR');
  }
}

export async function resolveCalculationReferences(
  organizationId: string,
  activity: ActivityData,
  factorVersionId?: string,
  gwpSetId?: string,
): Promise<CalculationReferenceResult> {
  assertUuid(organizationId, 'organizationId');
  if (activity.organizationId !== organizationId) throw new Error('INVALID_CALCULATION_RELATIONSHIP');
  if (factorVersionId) assertUuid(factorVersionId, 'factorVersionId');
  if (gwpSetId) assertUuid(gwpSetId, 'gwpSetId');

  const factorResult = await getPool().query(
    `SELECT efv.id::text, efv.emission_factor_id::text, efv.version_number,
            efv.co2_factor::text, efv.ch4_factor::text, efv.n2o_factor::text,
            efv.co2e_factor::text, efv.factor_unit, efv.source, efv.source_year,
            efv.geography, efv.status, efv.effective_start::text, efv.effective_end::text,
            ef.activity_type, ef.input_unit
       FROM emission_factor_versions efv
       JOIN emission_factors ef ON ef.id = efv.emission_factor_id
      WHERE ef.activity_type = $1
        AND efv.status = 'ACTIVE'
        AND ($2::uuid IS NULL OR efv.id = $2)
      ORDER BY efv.version_number DESC
      LIMIT 1`,
    [activity.activityType, factorVersionId || null],
  );
  const factorRow = factorResult.rows[0];
  if (!factorRow) throw new Error('FACTOR_NOT_FOUND');
  const factorVersion = mapFactorVersion(factorRow);

  const gwpResult = await getPool().query(
    `SELECT id::text, code, name, assessment_report, publication_year, is_default
       FROM gwp_sets
      WHERE ($1::uuid IS NOT NULL AND id = $1)
         OR ($1::uuid IS NULL AND is_default = true)
      ORDER BY is_default DESC, publication_year DESC, code
      LIMIT 1`,
    [gwpSetId || null],
  );
  const gwpRow = gwpResult.rows[0];
  if (!gwpRow) throw new Error('GWP_SET_NOT_FOUND');
  const gwpSet = mapGwpSet(gwpRow);
  const values = await getPool().query(
    `SELECT id::text, gwp_set_id::text, gas, gwp_100yr::text
       FROM gwp_values WHERE gwp_set_id = $1 ORDER BY gas`,
    [gwpSet.id],
  );
  return {
    factorVersion,
    factorInputUnit: String(factorRow.input_unit),
    gwpSet,
    gwpValues: values.rows.map(mapGwpValue),
  };
}

export async function persistCalculation(
  organizationId: string,
  userId: string,
  activity: ActivityData,
  output: { calculation: Calculation; emissionRecord: EmissionRecord },
): Promise<{ calculation: Calculation; emissionRecord: EmissionRecord }> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(userId, 'userId');
  if (
    activity.organizationId !== organizationId ||
    output.calculation.organizationId !== organizationId ||
    output.emissionRecord.organizationId !== organizationId ||
    output.calculation.activityDataId !== activity.id ||
    output.calculation.reportingPeriodId !== activity.reportingPeriodId ||
    output.emissionRecord.calculationId !== output.calculation.id ||
    output.emissionRecord.reportingPeriodId !== activity.reportingPeriodId ||
    output.emissionRecord.facilityId !== activity.facilityId ||
    output.emissionRecord.scope !== activity.scope ||
    output.emissionRecord.category !== activity.category ||
    output.emissionRecord.status !== 'ACTIVE'
  ) {
    throw new Error('INVALID_CALCULATION_RELATIONSHIP');
  }

  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const activityLock = await client.query(
      `SELECT a.id, a.organization_id, a.reporting_period_id, a.facility_id, a.scope, a.category
         FROM activity_data a
        WHERE a.id = $1 AND a.organization_id = $2
        FOR SHARE`,
      [activity.id, organizationId],
    );
    const lockedActivity = activityLock.rows[0];
    if (
      !lockedActivity ||
      lockedActivity.reporting_period_id !== activity.reportingPeriodId ||
      lockedActivity.facility_id !== activity.facilityId ||
      lockedActivity.scope !== activity.scope ||
      lockedActivity.category !== activity.category
    ) {
      throw new Error('INVALID_CALCULATION_RELATIONSHIP');
    }
    const factor = await client.query(
      `SELECT efv.id, efv.emission_factor_id, efv.factor_unit, efv.source, efv.source_year,
              efv.version_number, efv.co2e_factor::text, efv.status
         FROM emission_factor_versions efv
        WHERE efv.id = $1 AND efv.status = 'ACTIVE'`,
      [output.calculation.factorVersionId],
    );
    if (!factor.rows[0]) throw new Error('INVALID_CALCULATION_REFERENCE');
    const gwp = await client.query(
      `SELECT id, name FROM gwp_sets WHERE id = $1`,
      [output.calculation.gwpSetId],
    );
    if (!gwp.rows[0]) throw new Error('INVALID_CALCULATION_REFERENCE');
    const persistedFactorSource = `${factor.rows[0].source} (${factor.rows[0].source_year})`;
    if (
      output.calculation.factorUnit !== factor.rows[0].factor_unit ||
      output.calculation.factorVersion !== Number(factor.rows[0].version_number) ||
      output.calculation.factorValue !== Number(factor.rows[0].co2e_factor) ||
      output.calculation.factorSource !== persistedFactorSource ||
      output.calculation.gwpName !== gwp.rows[0].name
    ) {
      throw new Error('INVALID_CALCULATION_SNAPSHOT');
    }

    await client.query(
      `UPDATE emission_records er
          SET status = 'SUPERSEDED'
        WHERE er.organization_id = $1
          AND er.status = 'ACTIVE'
          AND er.calculation_id IN (
            SELECT c.id FROM calculations c
             WHERE c.organization_id = $1 AND c.activity_data_id = $2
          )`,
      [organizationId, activity.id],
    );

    await client.query(
      `INSERT INTO calculations
        (id, organization_id, activity_data_id, reporting_period_id, factor_version_id,
         gwp_set_id, original_quantity, original_unit, normalized_quantity, normalized_unit,
         conversion_factor, factor_value, factor_unit, factor_source, factor_version_number,
         gwp_name, total_co2e_kg, total_co2e_tonnes, calculation_hash, calculated_at,
         calculated_by, factor_id)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22)`,
      [
        output.calculation.id, organizationId, activity.id, activity.reportingPeriodId,
        output.calculation.factorVersionId, output.calculation.gwpSetId,
        output.calculation.originalQuantity, output.calculation.originalUnit,
        output.calculation.normalizedQuantity, output.calculation.normalizedUnit,
        output.calculation.conversionFactor, output.calculation.factorValue,
        output.calculation.factorUnit, output.calculation.factorSource, factor.rows[0].version_number,
        output.calculation.gwpName, output.calculation.totalCo2eKg,
        output.calculation.totalCo2eTonnes, output.calculation.calculationHash,
        output.calculation.calculatedAt, userId, factor.rows[0].emission_factor_id,
      ],
    );

    for (const gas of output.calculation.gasResults) {
      await client.query(
        `INSERT INTO calculation_gas_results
           (calculation_id, gas, raw_gas_emission_kg, gwp_applied, co2e_kg)
         VALUES ($1,$2,$3,$4,$5)`,
        [output.calculation.id, gas.gas, gas.rawGasEmissionKg, gas.gwpApplied, gas.co2eKg],
      );
    }

    await client.query(
      `INSERT INTO emission_records
        (id, organization_id, reporting_period_id, facility_id, calculation_id,
         scope, category, scope2_type, co2e_tonnes, status, created_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
      [
        output.emissionRecord.id, organizationId, activity.reportingPeriodId,
        activity.facilityId, output.calculation.id, output.emissionRecord.scope,
        output.emissionRecord.category, output.emissionRecord.scope2Type || null,
        output.emissionRecord.co2eTonnes, output.emissionRecord.status,
        output.emissionRecord.createdAt,
      ],
    );
    await client.query(
      `UPDATE activity_data SET status = 'CALCULATED', updated_at = CURRENT_TIMESTAMP
        WHERE id = $1 AND organization_id = $2`,
      [activity.id, organizationId],
    );
    await client.query('COMMIT');
    return {
      calculation: { ...output.calculation, calculatedBy: userId },
      emissionRecord: { ...output.emissionRecord, organizationId },
    };
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch { /* connection closed */ }
    if (error && typeof error === 'object' && 'code' in error && error.code === '23503') {
      throw new Error('INVALID_CALCULATION_RELATIONSHIP');
    }
    throw error;
  } finally {
    client.release();
  }
}

export async function getCalculation(organizationId: string, calculationId: string): Promise<Calculation | undefined> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(calculationId, 'calculationId');
  const result = await getPool().query(
    `${CALCULATION_SELECT} WHERE c.organization_id = $1 AND c.id = $2`,
    [organizationId, calculationId],
  );
  return result.rows[0] ? mapCalculation(result.rows[0]) : undefined;
}

export async function listCalculations(
  organizationId: string,
  filters: { activityId?: string; periodId?: string } = {},
): Promise<Calculation[]> {
  assertUuid(organizationId, 'organizationId');
  if (filters.activityId) assertUuid(filters.activityId, 'activityId');
  if (filters.periodId) assertUuid(filters.periodId, 'periodId');
  const result = await getPool().query(
    `${CALCULATION_SELECT}
      WHERE c.organization_id = $1
        AND ($2::uuid IS NULL OR c.activity_data_id = $2)
        AND ($3::uuid IS NULL OR c.reporting_period_id = $3)
      ORDER BY c.calculated_at DESC, c.id DESC`,
    [organizationId, filters.activityId || null, filters.periodId || null],
  );
  return result.rows.map(mapCalculation);
}

export async function listLatestCalculationsByActivities(
  organizationId: string,
  activityIds: string[],
): Promise<Map<string, Calculation>> {
  assertUuid(organizationId, 'organizationId');
  if (!activityIds.length) return new Map();
  for (const id of activityIds) assertUuid(id, 'activityId');
  const result = await getPool().query(
    `${CALCULATION_SELECT}
      WHERE c.organization_id = $1
        AND c.id = (
          SELECT c2.id FROM calculations c2
           WHERE c2.organization_id = c.organization_id AND c2.activity_data_id = c.activity_data_id
           ORDER BY c2.calculated_at DESC, c2.id DESC LIMIT 1
        )
        AND c.activity_data_id = ANY($2::uuid[])`,
    [organizationId, activityIds],
  );
  return new Map(result.rows.map((row: any) => [String(row.activity_data_id), mapCalculation(row)]));
}

export async function listEmissionRecords(
  organizationId: string,
  filters: { periodId?: string; scope?: string; status?: string; calculationId?: string } = {},
): Promise<EmissionRecord[]> {
  assertUuid(organizationId, 'organizationId');
  if (filters.periodId) assertUuid(filters.periodId, 'periodId');
  if (filters.calculationId) assertUuid(filters.calculationId, 'calculationId');
  validateScopeFilter(filters.scope);
  if (filters.status && !['ACTIVE', 'SUPERSEDED', 'VOIDED'].includes(filters.status)) {
    throw new Error('VALIDATION_ERROR');
  }
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, reporting_period_id::text, facility_id::text,
            calculation_id::text, scope, category, scope2_type, co2e_tonnes::text,
            status, created_at::text
       FROM emission_records
      WHERE organization_id = $1
        AND ($2::uuid IS NULL OR reporting_period_id = $2)
        AND ($3::text IS NULL OR scope = $3)
        AND ($4::text IS NULL OR status = $4)
        AND ($5::uuid IS NULL OR calculation_id = $5)
      ORDER BY created_at DESC, id DESC`,
    [organizationId, filters.periodId || null, filters.scope || null, filters.status || null, filters.calculationId || null],
  );
  return result.rows.map(mapEmission);
}

export async function getEmissionRecord(organizationId: string, emissionId: string): Promise<EmissionRecord | undefined> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(emissionId, 'emissionId');
  const result = await getPool().query(
    `SELECT id::text, organization_id::text, reporting_period_id::text, facility_id::text,
            calculation_id::text, scope, category, scope2_type, co2e_tonnes::text,
            status, created_at::text
       FROM emission_records WHERE organization_id = $1 AND id = $2`,
    [organizationId, emissionId],
  );
  return result.rows[0] ? mapEmission(result.rows[0]) : undefined;
}

export async function listActivityEmissionRecords(organizationId: string, activityId: string): Promise<EmissionRecord[]> {
  assertUuid(organizationId, 'organizationId');
  assertUuid(activityId, 'activityId');
  const result = await getPool().query(
    `SELECT er.id::text, er.organization_id::text, er.reporting_period_id::text,
            er.facility_id::text, er.calculation_id::text, er.scope, er.category,
            er.scope2_type, er.co2e_tonnes::text, er.status, er.created_at::text
       FROM emission_records er
       JOIN calculations c ON c.id = er.calculation_id AND c.organization_id = er.organization_id
      WHERE er.organization_id = $1 AND c.activity_data_id = $2
      ORDER BY er.created_at DESC, er.id DESC`,
    [organizationId, activityId],
  );
  return result.rows.map(mapEmission);
}

export async function closeCalculationPersistence(): Promise<void> {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}
