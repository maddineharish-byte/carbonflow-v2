import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import bcrypt from 'bcryptjs';
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import { executeCalculation, normalizeUnits } from './calc.ts';
import {
  closeCalculationPersistence,
  getCalculation,
  listCalculations,
  listEmissionRecords,
  listLatestCalculationsByActivities,
  persistCalculation,
  resolveCalculationReferences,
} from './calculation-repository.ts';

const { Pool } = pg;
const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME || 'carbonflow_dev',
  user: process.env.DB_USER || 'postgres',
  password: process.env.DB_PASSWORD,
  connectionTimeoutMillis: 5000,
});
const backendScript = fileURLToPath(new URL('./test-backend-process.ts', import.meta.url));
const workspace = path.dirname(path.dirname(backendScript));

async function startProductionBackend(environment: Record<string, string> = {}) {
  const child = spawn(process.execPath, ['--import', 'tsx', backendScript], {
    cwd: workspace,
    env: {
      ...process.env,
      NODE_ENV: 'production',
      JWT_SECRET: 'task-2-5-jwt-secret',
      REFRESH_TOKEN_SECRET: 'task-2-5-refresh-secret',
      ...environment,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const port = await new Promise<number>((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    child.stdout!.on('data', (chunk) => {
      stdout += String(chunk);
      const match = stdout.match(/READY:(\d+)/);
      if (match) resolve(Number(match[1]));
    });
    child.stderr!.on('data', (chunk) => { stderr += String(chunk); });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (!stdout.includes('READY:')) reject(new Error(`Backend exited before readiness (${code}): ${stderr}`));
    });
  });
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    process: child as ChildProcess,
    stop: async () => {
      if (child.exitCode === null && child.signalCode === null) {
        await new Promise<void>((resolve) => {
          const timer = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 3000);
          child.once('exit', () => { clearTimeout(timer); resolve(); });
          child.kill('SIGTERM');
        });
      }
    },
  };
}

async function login(baseUrl: string, email: string, password: string, organizationId: string) {
  const response = await fetch(`${baseUrl}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password, organizationId }),
  });
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return JSON.parse(text) as { data: { accessToken: string } };
}

async function jsonRequest(
  baseUrl: string,
  pathname: string,
  token: string,
  options: { method?: string; body?: Record<string, unknown> } = {},
) {
  return fetch(`${baseUrl}/api/v1${pathname}`, {
    method: options.method || 'GET',
    headers: {
      Authorization: `Bearer ${token}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
}

after(async () => {
  await closeCalculationPersistence();
  await pool.end();
});

/**
 * Removes rows created by this test file. Organizations are deleted first because
 * calculations, emission records, activities, facilities, reporting periods, and
 * memberships cascade from them; the fixture factor rows can only be deleted after
 * no calculation references them.
 */
async function cleanupTaskFixtures(): Promise<void> {
  await pool.query(`DELETE FROM organizations WHERE name LIKE 'Task 2.5 %'`)
    .catch((error: any) => console.error('TASK 2.5 organization cleanup failed:', error?.message));
  await pool.query(`DELETE FROM users WHERE email LIKE 'task-2-5-%@example.test'`)
    .catch((error: any) => console.error('TASK 2.5 user cleanup failed:', error?.message));
  await pool.query(
    `DELETE FROM emission_factor_versions
      WHERE emission_factor_id IN (SELECT id FROM emission_factors WHERE activity_type = 'T25_HISTORY_GAS')`,
  ).catch((error: any) => console.error('TASK 2.5 factor version cleanup failed:', error?.message));
  await pool.query(`DELETE FROM emission_factors WHERE activity_type = 'T25_HISTORY_GAS'`)
    .catch((error: any) => console.error('TASK 2.5 factor cleanup failed:', error?.message));
}

test('TASK 2.5 calculations and emission records persist in PostgreSQL with accounting semantics', async (t) => {
  const suffix = randomUUID();
  const email = `task-2-5-${suffix}@example.test`;
  const otherEmail = `task-2-5-other-${suffix}@example.test`;
  const password = `Task-2-5-password-${suffix}`;
  const userIds: string[] = [];
  const organizationIds: string[] = [];
  const historyFactorIds: string[] = [];
  let backend: Awaited<ReturnType<typeof startProductionBackend>> | undefined;

  const insertFixture = async () => {
    const user = await pool.query(
      `INSERT INTO users (email, password_hash, full_name) VALUES ($1,$2,$3) RETURNING id::text`,
      [email, bcrypt.hashSync(password, 4), 'Task 2.5 Tenant A'],
    );
    const otherUser = await pool.query(
      `INSERT INTO users (email, password_hash, full_name) VALUES ($1,$2,$3) RETURNING id::text`,
      [otherEmail, bcrypt.hashSync(password, 4), 'Task 2.5 Tenant B'],
    );
    userIds.push(user.rows[0].id, otherUser.rows[0].id);

    const org = await pool.query(
      `INSERT INTO organizations (name, tax_id, country) VALUES ($1,$2,'US') RETURNING id::text`,
      [`Task 2.5 Organization A ${suffix}`, `TASK25-A-${suffix}`],
    );
    const otherOrg = await pool.query(
      `INSERT INTO organizations (name, tax_id, country) VALUES ($1,$2,'US') RETURNING id::text`,
      [`Task 2.5 Organization B ${suffix}`, `TASK25-B-${suffix}`],
    );
    organizationIds.push(org.rows[0].id, otherOrg.rows[0].id);
    const role = await pool.query(`SELECT id::text FROM roles WHERE name = 'COMPANY_ADMIN'`);
    await pool.query(
      `INSERT INTO organization_memberships (organization_id, user_id, role_id) VALUES ($1,$2,$3),($4,$5,$3)`,
      [org.rows[0].id, user.rows[0].id, role.rows[0].id, otherOrg.rows[0].id, otherUser.rows[0].id],
    );

    const facility = await pool.query(
      `INSERT INTO facilities (organization_id,name,facility_code,country,grid_region)
       VALUES ($1,'Task 2.5 Facility A',$2,'US','TEST') RETURNING id::text`,
      [org.rows[0].id, `T25-A-${suffix}`],
    );
    const otherFacility = await pool.query(
      `INSERT INTO facilities (organization_id,name,facility_code,country,grid_region)
       VALUES ($1,'Task 2.5 Facility B',$2,'US','TEST') RETURNING id::text`,
      [otherOrg.rows[0].id, `T25-B-${suffix}`],
    );
    const period = await pool.query(
      `INSERT INTO reporting_periods (organization_id,name,start_date,end_date,status)
       VALUES ($1,'Task 2.5 Period A','2025-01-01','2025-12-31','OPEN') RETURNING id::text`,
      [org.rows[0].id],
    );
    const otherPeriod = await pool.query(
      `INSERT INTO reporting_periods (organization_id,name,start_date,end_date,status)
       VALUES ($1,'Task 2.5 Period B','2025-01-01','2025-12-31','OPEN') RETURNING id::text`,
      [otherOrg.rows[0].id],
    );
    return {
      userId: user.rows[0].id,
      otherUserId: otherUser.rows[0].id,
      organizationId: org.rows[0].id,
      otherOrganizationId: otherOrg.rows[0].id,
      facilityId: facility.rows[0].id,
      otherFacilityId: otherFacility.rows[0].id,
      periodId: period.rows[0].id,
      otherPeriodId: otherPeriod.rows[0].id,
    };
  };

  try {
    await cleanupTaskFixtures();
    const fixture = await insertFixture();
    backend = await startProductionBackend({
      TEST_USER_ID: fixture.userId,
      TEST_USER_EMAIL: email,
      TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: fixture.organizationId,
      TEST_ROLE: 'COMPANY_ADMIN',
    });
    const sessionA = await login(backend.baseUrl, email, password, fixture.organizationId);
    const tokenA = sessionA.data.accessToken;
    const sessionB = await login(backend.baseUrl, otherEmail, password, fixture.otherOrganizationId);
    const tokenB = sessionB.data.accessToken;

    const referenceGwpResponse = await jsonRequest(backend.baseUrl, '/reference/gwp-sets', tokenA);
    assert.equal(referenceGwpResponse.status, 200);
    const referenceGwpSets = (await referenceGwpResponse.json()).data;
    const referenceAr6 = referenceGwpSets.find((set: any) => set.code === 'IPCC_AR6');
    assert.equal(referenceAr6.isDefault, true);
    assert.ok(referenceAr6.values.some((value: any) => value.gas === 'CH4'));
    const referenceFactorsResponse = await jsonRequest(backend.baseUrl, '/reference/emission-factors', tokenA);
    assert.equal(referenceFactorsResponse.status, 200);
    const referenceFactors = (await referenceFactorsResponse.json()).data;
    const referenceNaturalGas = referenceFactors.find((factor: any) => factor.activityType === 'NATURAL_GAS');
    assert.match(referenceNaturalGas.id, /^[0-9a-f-]{36}$/i);
    assert.equal(referenceNaturalGas.versions[0].factorUnit, 'kgCO2e/kWh');

    const activityPayload = (overrides: Record<string, unknown> = {}) => ({
      reportingPeriodId: fixture.periodId,
      facilityId: fixture.facilityId,
      scope: 'SCOPE_1',
      category: 'STATIONARY_COMBUSTION',
      activityType: 'NATURAL_GAS',
      quantity: 1000,
      unit: 'kWh',
      startDate: '2025-01-01',
      endDate: '2025-12-31',
      source: 'Task 2.5 PostgreSQL integration test',
      notes: '',
      ...overrides,
    });

    const createActivity = async (body: Record<string, unknown>) => {
      const response = await jsonRequest(backend!.baseUrl, '/activity-data', tokenA, { method: 'POST', body });
      assert.equal(response.status, 201, await response.clone().text());
      return (await response.json()).data;
    };

    const scope1Activity = await createActivity(activityPayload());
    const locationActivity = await createActivity(activityPayload({
      scope: 'SCOPE_2',
      category: 'ELECTRICITY_LOCATION',
      activityType: 'GRID_ELECTRICITY_US',
      unit: 'kWh',
      quantity: 2000,
    }));
    const marketActivity = await createActivity(activityPayload({
      scope: 'SCOPE_2',
      category: 'ELECTRICITY_MARKET',
      activityType: 'GREEN_POWER_TARIFF',
      unit: 'kWh',
      quantity: 3000,
    }));

    const runCalculation = async (activityId: string, body: Record<string, unknown> = {}) => {
      const response = await jsonRequest(backend!.baseUrl, '/calculations/run', tokenA, {
        method: 'POST',
        body: { activityDataId: activityId, ...body },
      });
      assert.equal(response.status, 200, await response.clone().text());
      return (await response.json()).data as { calculation: any; emissionRecord: any };
    };

    const scope1Run = await runCalculation(scope1Activity.id);
    const scope1Factor = await pool.query(
      `SELECT efv.co2_factor::text, efv.ch4_factor::text, efv.n2o_factor::text, efv.factor_unit, efv.source, efv.source_year, efv.version_number, efv.id::text
         FROM emission_factor_versions efv
         JOIN emission_factors ef ON ef.id = efv.emission_factor_id
        WHERE ef.activity_type = 'NATURAL_GAS' AND efv.status = 'ACTIVE'
        ORDER BY efv.version_number DESC LIMIT 1`,
    );
    const gwpValues = await pool.query(
      `SELECT gas, gwp_100yr::text FROM gwp_values
        WHERE gwp_set_id = (SELECT id FROM gwp_sets WHERE is_default = true)
        ORDER BY gas`,
    );
    const gwp = new Map<string, number>(gwpValues.rows.map((row: any) => [row.gas.toUpperCase(), Number(row.gwp_100yr)] as [string, number]));
    const factorRow = scope1Factor.rows[0];
    const expectedScope1Kg =
      1000 * Number(factorRow.co2_factor) +
      1000 * Number(factorRow.ch4_factor) * (gwp.get('CH4') || 28) +
      1000 * Number(factorRow.n2o_factor) * (gwp.get('N2O') || 273);
    assert.ok(Math.abs(scope1Run.calculation.totalCo2eKg - expectedScope1Kg) < 0.0001,
      `expected ${expectedScope1Kg}, got ${scope1Run.calculation.totalCo2eKg}`);
    assert.equal(scope1Run.calculation.originalUnit, 'kWh');
    assert.equal(scope1Run.calculation.normalizedUnit, 'KWH');
    assert.equal(scope1Run.calculation.conversionFactor, 1);
    assert.equal(scope1Run.calculation.factorUnit, factorRow.factor_unit);
    assert.equal(scope1Run.calculation.factorVersion, factorRow.version_number);
    assert.equal(scope1Run.calculation.factorSource, `${factorRow.source} (${factorRow.source_year})`);
    assert.equal(scope1Run.calculation.gwpName, 'IPCC Sixth Assessment Report (AR6)');
    assert.ok(scope1Run.calculation.calculationHash);
    assert.ok(scope1Run.calculation.gasResults.length >= 3);
    assert.equal(scope1Run.emissionRecord.status, 'ACTIVE');
    assert.equal(scope1Run.emissionRecord.scope2Type, undefined);

    const unitNormalization = normalizeUnits(2, 'MWh', 'kWh');
    assert.equal(unitNormalization.normalizedQuantity.toNumber(), 2000);
    assert.equal(unitNormalization.conversionFactor.toNumber(), 1000);
    assert.equal(unitNormalization.normalizedUnit, 'kWh');

    const locationRun = await runCalculation(locationActivity.id);
    const marketRun = await runCalculation(marketActivity.id);
    assert.equal(locationRun.emissionRecord.scope2Type, 'LOCATION_BASED');
    assert.equal(marketRun.emissionRecord.scope2Type, 'MARKET_BASED');
    assert.ok(locationRun.emissionRecord.co2eTonnes > 0, 'location-based Scope 2 must be calculated');
    assert.equal(marketRun.emissionRecord.co2eTonnes, 0, 'market-based result must not be fabricated');
    assert.notEqual(locationRun.emissionRecord.co2eTonnes, marketRun.emissionRecord.co2eTonnes);

    const emissionsResponse = await jsonRequest(backend.baseUrl, '/emissions', tokenA);
    assert.equal(emissionsResponse.status, 200);
    const emissionsBody = (await emissionsResponse.json()).data;
    assert.equal(emissionsBody.records.length, 3);
    assert.ok(emissionsBody.summary.scope1Tonnes > 0);
    assert.ok(emissionsBody.summary.scope2LocationTonnes > 0);
    assert.equal(emissionsBody.summary.scope2MarketTonnes, 0);
    assert.ok(Math.abs(emissionsBody.summary.totalLocationBasedTonnes - (emissionsBody.summary.scope1Tonnes + emissionsBody.summary.scope2LocationTonnes)) < 0.0001);
    assert.ok(Math.abs(emissionsBody.summary.totalMarketBasedTonnes - emissionsBody.summary.scope1Tonnes) < 0.0001);

    const dashboardResponse = await jsonRequest(backend.baseUrl, '/analytics/dashboard', tokenA);
    assert.equal(dashboardResponse.status, 200, await dashboardResponse.clone().text());
    const dashboardBody = (await dashboardResponse.json()).data;
    assert.ok(dashboardBody.emissions.scope1Tonnes > 0);
    assert.ok(dashboardBody.facilities.some((facility: any) => facility.name === 'Task 2.5 Facility A'));

    const exportResponse = await fetch(`${backend.baseUrl}/api/v1/reports/export-csv`, {
      headers: { Authorization: `Bearer ${tokenA}` },
    });
    assert.equal(exportResponse.status, 200);
    const exportCsv = await exportResponse.text();
    assert.ok(exportCsv.includes(scope1Run.calculation.calculationHash));
    assert.ok(exportCsv.includes('Task 2.5 Facility A'));

    const persistedCalculations = await listCalculations(fixture.organizationId, { periodId: fixture.periodId });
    assert.equal(persistedCalculations.length, 3);
    const persistedScope1 = await getCalculation(fixture.organizationId, scope1Run.calculation.id);
    assert.ok(persistedScope1);
    assert.equal(persistedScope1.factorVersionId, factorRow.id);
    assert.equal(persistedScope1.gwpName, 'IPCC Sixth Assessment Report (AR6)');
    assert.equal(persistedScope1.gasResults.length, scope1Run.calculation.gasResults.length);
    assert.equal(await getCalculation(fixture.otherOrganizationId, scope1Run.calculation.id), undefined);
    assert.equal((await listCalculations(fixture.otherOrganizationId)).length, 0);

    const latest = await listLatestCalculationsByActivities(fixture.organizationId, [
      scope1Activity.id,
      locationActivity.id,
      marketActivity.id,
    ]);
    assert.equal(latest.get(scope1Activity.id)?.id, scope1Run.calculation.id);
    assert.equal(await listLatestCalculationsByActivities(fixture.otherOrganizationId, [scope1Activity.id]).then((m) => m.size), 0);

    const crossTenantRun = await jsonRequest(backend.baseUrl, '/calculations/run', tokenB, {
      method: 'POST', body: { activityDataId: scope1Activity.id },
    });
    assert.equal(crossTenantRun.status, 404);
    const crossTenantEmissions = await jsonRequest(backend.baseUrl, '/emissions', tokenB);
    assert.equal(crossTenantEmissions.status, 200);
    assert.equal((await crossTenantEmissions.json()).data.records.length, 0);

    await assert.rejects(
      () => persistCalculation(fixture.otherOrganizationId, fixture.otherUserId, scope1Activity, scope1Run),
      (error: any) => error?.message === 'INVALID_CALCULATION_RELATIONSHIP',
    );
    await assert.rejects(
      () => resolveCalculationReferences(fixture.otherOrganizationId, scope1Activity),
      (error: any) => error?.message === 'INVALID_CALCULATION_RELATIONSHIP',
    );

    await assert.rejects(
      () => pool.query(
        `INSERT INTO calculations
           (organization_id, activity_data_id, reporting_period_id, factor_version_id, gwp_set_id,
            original_quantity, original_unit, normalized_quantity, normalized_unit, conversion_factor,
            factor_value, factor_unit, factor_source, factor_version_number, gwp_name,
            total_co2e_kg, total_co2e_tonnes, calculation_hash, factor_id)
         SELECT $1, a.id, a.reporting_period_id, efv.id, gs.id,
                1, 'kWh', 1, 'kWh', 1, 1, efv.factor_unit, 'x', 1, gs.name, 0, 0, 'x', efv.emission_factor_id
           FROM activity_data a, emission_factor_versions efv, gwp_sets gs
          WHERE a.id = $2 AND efv.status = 'ACTIVE' AND gs.is_default = true LIMIT 1`,
        [fixture.otherOrganizationId, scope1Activity.id],
      ),
      (error: any) => error?.code === '23503',
    );
    await assert.rejects(
      () => pool.query(
        `INSERT INTO calculations
           (organization_id, activity_data_id, reporting_period_id, factor_version_id, gwp_set_id,
            original_quantity, original_unit, normalized_quantity, normalized_unit, conversion_factor,
            factor_value, factor_unit, factor_source, factor_version_number, gwp_name,
            total_co2e_kg, total_co2e_tonnes, calculation_hash, factor_id)
         SELECT $1, a.id, $2, efv.id, gs.id,
                1, 'kWh', 1, 'kWh', 1, 1, efv.factor_unit, 'x', 1, gs.name, 0, 0, 'x', efv.emission_factor_id
           FROM activity_data a, emission_factor_versions efv, gwp_sets gs
          WHERE a.id = $3 AND efv.status = 'ACTIVE' AND gs.is_default = true LIMIT 1`,
        [fixture.organizationId, fixture.otherPeriodId, scope1Activity.id],
      ),
      (error: any) => error?.code === '23503',
    );
    await assert.rejects(
      () => pool.query(
        `INSERT INTO emission_records
           (organization_id, reporting_period_id, facility_id, calculation_id, scope, category, co2e_tonnes, status)
         VALUES ($1, $2, $3, $4, 'SCOPE_1', 'STATIONARY_COMBUSTION', 0, 'ACTIVE')`,
        [fixture.organizationId, fixture.periodId, fixture.otherFacilityId, scope1Run.calculation.id],
      ),
      (error: any) => error?.code === '23503',
    );
    await assert.rejects(
      () => pool.query(
        `INSERT INTO emission_records
           (organization_id, reporting_period_id, facility_id, calculation_id, scope, category, co2e_tonnes, status)
         VALUES ($1, $2, $3, $4, 'SCOPE_1', 'STATIONARY_COMBUSTION', 0, 'ACTIVE')`,
        [fixture.organizationId, fixture.otherPeriodId, fixture.facilityId, scope1Run.calculation.id],
      ),
      (error: any) => error?.code === '23503',
    );
    await assert.rejects(
      () => pool.query(
        `INSERT INTO emission_records
           (organization_id, reporting_period_id, facility_id, calculation_id, scope, category, co2e_tonnes, status)
         VALUES ($1, $2, $3, $4, 'SCOPE_1', 'STATIONARY_COMBUSTION', 0, 'ACTIVE')`,
        [fixture.otherOrganizationId, fixture.otherPeriodId, fixture.otherFacilityId, scope1Run.calculation.id],
      ),
      (error: any) => error?.code === '23503',
    );

    const invalidFactor = await jsonRequest(backend.baseUrl, '/calculations/run', tokenA, {
      method: 'POST',
      body: { activityDataId: scope1Activity.id, factorVersionId: randomUUID() },
    });
    assert.equal(invalidFactor.status, 400);
    assert.equal((await invalidFactor.json()).error.code, 'FACTOR_NOT_FOUND');
    const invalidGwp = await jsonRequest(backend.baseUrl, '/calculations/run', tokenA, {
      method: 'POST',
      body: { activityDataId: scope1Activity.id, gwpSetId: randomUUID() },
    });
    assert.equal(invalidGwp.status, 400);
    assert.equal((await invalidGwp.json()).error.code, 'GWP_SET_NOT_FOUND');

    const beforeRollback = await pool.query(
      `SELECT count(*)::int AS count FROM calculations WHERE organization_id = $1`,
      [fixture.organizationId],
    );
    const references = await resolveCalculationReferences(fixture.organizationId, scope1Activity);
    const rollbackOutput = executeCalculation({
      activityData: scope1Activity,
      factorVersion: references.factorVersion,
      factorInputUnit: references.factorInputUnit,
      gwpSet: references.gwpSet,
      gwpValues: references.gwpValues,
      userId: fixture.userId,
    });
    rollbackOutput.emissionRecord.id = 'not-a-uuid';
    await assert.rejects(() => persistCalculation(fixture.organizationId, fixture.userId, scope1Activity, rollbackOutput));
    const afterRollback = await pool.query(
      `SELECT count(*)::int AS count FROM calculations WHERE organization_id = $1`,
      [fixture.organizationId],
    );
    assert.equal(afterRollback.rows[0].count, beforeRollback.rows[0].count);

    const secondScope1Run = await runCalculation(scope1Activity.id);
    assert.notEqual(secondScope1Run.calculation.id, scope1Run.calculation.id);
    const lifecycle = await pool.query(
      `SELECT er.status, count(*)::int AS count
         FROM emission_records er
         JOIN calculations c ON c.id = er.calculation_id
        WHERE er.organization_id = $1 AND c.activity_data_id = $2
        GROUP BY er.status ORDER BY er.status`,
      [fixture.organizationId, scope1Activity.id],
    );
    const lifecycleMap = new Map(lifecycle.rows.map((row: any) => [row.status, row.count]));
    assert.equal(lifecycleMap.get('ACTIVE'), 1);
    assert.equal(lifecycleMap.get('SUPERSEDED'), 1);
    const activeRecords = await listEmissionRecords(fixture.organizationId, { periodId: fixture.periodId, status: 'ACTIVE' });
    assert.equal(activeRecords.length, 3);
    const supersededRecords = await listEmissionRecords(fixture.organizationId, { status: 'SUPERSEDED' });
    assert.equal(supersededRecords.length, 1);
    assert.equal((await listEmissionRecords(fixture.otherOrganizationId)).length, 0);
    const scopeFilter = await listEmissionRecords(fixture.organizationId, { scope: 'SCOPE_2' });
    assert.equal(scopeFilter.length, 2);
    await assert.rejects(() => listEmissionRecords(fixture.organizationId, { scope: 'INVALID_SCOPE' as any }));

    const batchResponse = await jsonRequest(backend.baseUrl, '/calculations/batch-run', tokenA, {
      method: 'POST', body: { reportingPeriodId: fixture.periodId },
    });
    assert.equal(batchResponse.status, 200, await batchResponse.clone().text());
    const batchBody = (await batchResponse.json()).data;
    assert.equal(batchBody.total, 3);
    assert.equal(batchBody.processed, 3);
    const batchActive = await pool.query(
      `SELECT count(*)::int AS count FROM emission_records
        WHERE organization_id = $1 AND status = 'ACTIVE' AND reporting_period_id = $2`,
      [fixture.organizationId, fixture.periodId],
    );
    assert.equal(batchActive.rows[0].count, 3);

    const historyFactor = await pool.query(
      `INSERT INTO emission_factors (scope, category, activity_type, fuel_or_activity, input_unit)
       VALUES ('SCOPE_1','STATIONARY_COMBUSTION','T25_HISTORY_GAS','Task 2.5 historical factor','kWh')
       RETURNING id::text`,
    ).catch((error: any) => { throw error; });
    const historyFactorId = historyFactor.rows[0].id;
    historyFactorIds.push(historyFactorId);
    const historyVersion1 = await pool.query(
      `INSERT INTO emission_factor_versions
         (emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor,
          factor_unit, source, source_year, geography, status, effective_start)
       VALUES ($1, 1, 0.1, 0, 0, 0.1, 'kgCO2e/kWh', 'Task 2.5 historical source', 2024, 'GLOBAL', 'ACTIVE', '2025-01-01')
       RETURNING id::text`,
      [historyFactorId],
    );
    historyFactorIds.push(historyVersion1.rows[0].id);
    const historyActivity = await createActivity(activityPayload({
      activityType: 'T25_HISTORY_GAS',
      quantity: 2,
      unit: 'MWh',
    }));
    const historyRun = await runCalculation(historyActivity.id);
    assert.equal(historyRun.calculation.originalUnit, 'MWh');
    assert.equal(historyRun.calculation.normalizedUnit, 'kWh');
    assert.equal(historyRun.calculation.conversionFactor, 1000);
    assert.equal(historyRun.calculation.normalizedQuantity, 2000);
    assert.equal(historyRun.calculation.factorVersion, 1);
    assert.equal(historyRun.calculation.factorValue, 0.1);

    const historyVersion2 = await pool.query(
      `INSERT INTO emission_factor_versions
         (emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor,
          factor_unit, source, source_year, geography, status, effective_start)
       VALUES ($1, 2, 0.9, 0, 0, 0.9, 'kgCO2e/kWh', 'Task 2.5 changed source', 2025, 'GLOBAL', 'ACTIVE', '2025-02-01')
       RETURNING id::text`,
      [historyFactorId],
    );
    historyFactorIds.push(historyVersion2.rows[0].id);
    const historyPersisted = await getCalculation(fixture.organizationId, historyRun.calculation.id);
    assert.ok(historyPersisted);
    assert.equal(historyPersisted.factorVersionId, historyVersion1.rows[0].id);
    assert.equal(historyPersisted.factorValue, 0.1);
    assert.equal(historyPersisted.factorVersion, 1);
    assert.equal(historyPersisted.factorSource, 'Task 2.5 historical source (2024)');
    assert.equal(historyPersisted.totalCo2eKg, historyRun.calculation.totalCo2eKg);

    await backend.stop();
    backend = await startProductionBackend({
      TEST_USER_ID: fixture.userId,
      TEST_USER_EMAIL: email,
      TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: fixture.organizationId,
      TEST_ROLE: 'COMPANY_ADMIN',
    });
    const restartedSession = await login(backend.baseUrl, email, password, fixture.organizationId);
    const restartedToken = restartedSession.data.accessToken;
    const restartedCalculations = await listCalculations(fixture.organizationId, { periodId: fixture.periodId });
    assert.equal(restartedCalculations.length, 8);
    assert.ok(restartedCalculations.some((calculation) => calculation.id === scope1Run.calculation.id));
    const restartedEmissions = await jsonRequest(backend.baseUrl, '/emissions', restartedToken);
    assert.equal(restartedEmissions.status, 200);
    assert.equal((await restartedEmissions.json()).data.records.length, 4);
    const restartedActivities = await jsonRequest(backend.baseUrl, `/activity-data?periodId=${fixture.periodId}`, restartedToken);
    assert.equal(restartedActivities.status, 200);
    const restartedActivityItems = (await restartedActivities.json()).data;
    assert.equal(restartedActivityItems.length, 4);
    assert.equal(restartedActivityItems.every((item: any) => item.calculation && item.calculation.id), true);
  } finally {
    await backend?.stop();
    await cleanupTaskFixtures();
  }
});
