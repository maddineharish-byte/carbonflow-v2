import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs/promises';
import bcrypt from 'bcryptjs';
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import {
  closeActivityPersistence,
  findActivityData,
  updateActivityStatus,
} from './activity-repository.ts';
import {
  closeEvidencePersistence,
  findEvidence,
} from './evidence-repository.ts';

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
      JWT_SECRET: 'task-2-4-jwt-secret',
      REFRESH_TOKEN_SECRET: 'task-2-4-refresh-secret',
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
    process: child,
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

async function uploadEvidence(
  baseUrl: string,
  token: string,
  fileName: string,
  contents: string,
  entityId?: string,
) {
  const form = new FormData();
  form.append('file', new Blob([contents], { type: 'application/pdf' }), fileName);
  if (entityId) {
    form.append('entityType', 'ACTIVITY_DATA');
    form.append('entityId', entityId);
  }
  return fetch(`${baseUrl}/api/v1/evidence/upload`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
}

after(async () => {
  await closeActivityPersistence();
  await closeEvidencePersistence();
  await pool.end();
});

test('TASK 2.4 activity and evidence metadata persist in PostgreSQL with tenant isolation', async (t) => {
  const suffix = randomUUID();
  const email = `task-2-4-${suffix}@example.test`;
  const otherEmail = `task-2-4-other-${suffix}@example.test`;
  const password = `Task-2-4-password-${suffix}`;
  const userIds: string[] = [];
  const organizationIds: string[] = [];
  const storagePaths: string[] = [];
  let backend: Awaited<ReturnType<typeof startProductionBackend>> | undefined;

  const insertFixture = async () => {
    const user = await pool.query(
      `INSERT INTO users (email, password_hash, full_name) VALUES ($1,$2,$3) RETURNING id::text`,
      [email, bcrypt.hashSync(password, 4), 'Task 2.4 Tenant A'],
    );
    const otherUser = await pool.query(
      `INSERT INTO users (email, password_hash, full_name) VALUES ($1,$2,$3) RETURNING id::text`,
      [otherEmail, bcrypt.hashSync(password, 4), 'Task 2.4 Tenant B'],
    );
    userIds.push(user.rows[0].id, otherUser.rows[0].id);

    const org = await pool.query(
      `INSERT INTO organizations (name, tax_id, country) VALUES ($1,$2,'US') RETURNING id::text`,
      [`Task 2.4 Organization A ${suffix}`, `TASK24-A-${suffix}`],
    );
    const otherOrg = await pool.query(
      `INSERT INTO organizations (name, tax_id, country) VALUES ($1,$2,'US') RETURNING id::text`,
      [`Task 2.4 Organization B ${suffix}`, `TASK24-B-${suffix}`],
    );
    organizationIds.push(org.rows[0].id, otherOrg.rows[0].id);
    const role = await pool.query(`SELECT id::text FROM roles WHERE name = 'COMPANY_ADMIN'`);
    await pool.query(
      `INSERT INTO organization_memberships (organization_id, user_id, role_id) VALUES ($1,$2,$3),($4,$5,$3)`,
      [org.rows[0].id, user.rows[0].id, role.rows[0].id, otherOrg.rows[0].id, otherUser.rows[0].id],
    );

    const facility = await pool.query(
      `INSERT INTO facilities (organization_id,name,facility_code,country,grid_region)
       VALUES ($1,'Task 2.4 Facility A',$2,'US','TEST') RETURNING id::text`,
      [org.rows[0].id, `T24-A-${suffix}`],
    );
    const otherFacility = await pool.query(
      `INSERT INTO facilities (organization_id,name,facility_code,country,grid_region)
       VALUES ($1,'Task 2.4 Facility B',$2,'US','TEST') RETURNING id::text`,
      [otherOrg.rows[0].id, `T24-B-${suffix}`],
    );
    const period = await pool.query(
      `INSERT INTO reporting_periods (organization_id,name,start_date,end_date,status)
       VALUES ($1,'Task 2.4 Period A','2025-01-01','2025-12-31','OPEN') RETURNING id::text`,
      [org.rows[0].id],
    );
    const otherPeriod = await pool.query(
      `INSERT INTO reporting_periods (organization_id,name,start_date,end_date,status)
       VALUES ($1,'Task 2.4 Period B','2025-01-01','2025-12-31','OPEN') RETURNING id::text`,
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

    const activityPayload = {
      reportingPeriodId: fixture.periodId,
      facilityId: fixture.facilityId,
      scope: 'SCOPE_1',
      category: 'STATIONARY_COMBUSTION',
      activityType: 'NATURAL_GAS',
      quantity: 1234.5,
      unit: 'kWh',
      startDate: '2025-01-01',
      endDate: '2025-12-31',
      source: 'Task 2.4 PostgreSQL integration test',
      notes: '',
    };
    const createResponse = await jsonRequest(backend.baseUrl, '/activity-data', tokenA, {
      method: 'POST', body: activityPayload,
    });
    assert.equal(createResponse.status, 201, await createResponse.clone().text());
    const activity = (await createResponse.json()).data;
    assert.equal(activity.quantity, 1234.5);
    assert.equal(activity.facilityName, 'Task 2.4 Facility A');

    await assert.rejects(
      () => pool.query(
        `INSERT INTO activity_data
           (organization_id,reporting_period_id,facility_id,scope,category,activity_type,quantity,unit,start_date,end_date,source)
         VALUES ($1,$2,$3,'SCOPE_1','STATIONARY_COMBUSTION','NATURAL_GAS',1,'kWh','2025-01-01','2025-01-31','V5 constraint test')`,
        [fixture.organizationId, fixture.periodId, fixture.otherFacilityId],
      ),
      (error: any) => error?.code === '23503',
    );
    await assert.rejects(
      () => pool.query(
        `INSERT INTO activity_data
           (organization_id,reporting_period_id,facility_id,scope,category,activity_type,quantity,unit,start_date,end_date,source)
         VALUES ($1,$2,$3,'SCOPE_1','STATIONARY_COMBUSTION','NATURAL_GAS',1,'kWh','2025-01-01','2025-01-31','V5 constraint test')`,
        [fixture.organizationId, fixture.otherPeriodId, fixture.facilityId],
      ),
      (error: any) => error?.code === '23503',
    );

    const invalidCases: Array<Record<string, unknown>> = [
      { ...activityPayload, quantity: 'not-a-number' },
      { ...activityPayload, quantity: -1 },
      { ...activityPayload, startDate: '2025-02-30' },
      { ...activityPayload, status: 'UNSUPPORTED' },
      { ...activityPayload, facilityId: fixture.otherFacilityId },
      { ...activityPayload, reportingPeriodId: fixture.otherPeriodId },
    ];
    for (const body of invalidCases) {
      const response = await jsonRequest(backend.baseUrl, '/activity-data', tokenA, { method: 'POST', body });
      assert.equal(response.status, 400, JSON.stringify(body));
    }

    const createB = await login(backend.baseUrl, otherEmail, password, fixture.otherOrganizationId);
    const tokenB = createB.data.accessToken;
    const activityBResponse = await jsonRequest(backend.baseUrl, '/activity-data', tokenB, {
      method: 'POST',
      body: { ...activityPayload, facilityId: fixture.otherFacilityId, reportingPeriodId: fixture.otherPeriodId },
    });
    assert.equal(activityBResponse.status, 201, await activityBResponse.clone().text());
    const activityB = (await activityBResponse.json()).data;

    const listA = await jsonRequest(
      backend.baseUrl,
      `/activity-data?periodId=${fixture.periodId}&facilityId=${fixture.facilityId}&scope=SCOPE_1`,
      tokenA,
    );
    assert.equal(listA.status, 200);
    const listAItems = (await listA.json()).data;
    assert.equal(listAItems.length, 1);
    assert.equal(listAItems[0].id, activity.id);
    assert.equal(await findActivityData(fixture.organizationId, activity.id)?.then((item) => item?.id), activity.id);
    assert.equal(await findActivityData(fixture.otherOrganizationId, activity.id), undefined);
    assert.equal(await updateActivityStatus(fixture.otherOrganizationId, activity.id, 'VALIDATED'), false);

    const crossTenantCalculation = await jsonRequest(backend.baseUrl, '/calculations/run', tokenB, {
      method: 'POST', body: { activityDataId: activity.id },
    });
    assert.equal(crossTenantCalculation.status, 404);
    const listB = await jsonRequest(backend.baseUrl, '/activity-data', tokenB);
    const listBItems = (await listB.json()).data;
    assert.equal(listBItems.some((item: any) => item.id === activity.id), false);
    assert.equal(listBItems.some((item: any) => item.id === activityB.id), true);

    const evidenceUpload = await uploadEvidence(
      backend.baseUrl, tokenA, 'task-2-4-evidence.pdf', '%PDF-1.7 task 2.4 evidence', activity.id,
    );
    assert.equal(evidenceUpload.status, 201, await evidenceUpload.clone().text());
    const evidenceBody = await evidenceUpload.json();
    const evidence = evidenceBody.data;
    assert.equal(evidence.sha256Hash.length, 64);
    assert.equal('storagePath' in evidence, false);
    const storedEvidence = await findEvidence(fixture.organizationId, evidence.id);
    assert.ok(storedEvidence);
    storagePaths.push(storedEvidence.storagePath);
    assert.equal(await findEvidence(fixture.otherOrganizationId, evidence.id), undefined);

    const evidenceListResponse = await jsonRequest(backend.baseUrl, '/evidence', tokenA);
    assert.equal(evidenceListResponse.status, 200);
    const evidenceList = (await evidenceListResponse.json()).data;
    assert.equal(evidenceList.some((item: any) => item.id === evidence.id), true);
    assert.equal(evidenceList.every((item: any) => !('storagePath' in item)), true);
    assert.deepEqual(evidenceList[0].links, [{ entityType: 'ACTIVITY_DATA', entityId: activity.id }]);

    const countBefore = await pool.query(`SELECT count(*)::int AS count FROM evidence_records WHERE organization_id = $1`, [fixture.organizationId]);
    const tenantBStorage = path.join(workspace, 'vault_storage', fixture.otherOrganizationId);
    const filesBefore = await fs.readdir(tenantBStorage).catch(() => []);
    const crossTenantLink = await uploadEvidence(
      backend.baseUrl, tokenB, 'cross-tenant.pdf', '%PDF-1.7 cross tenant', activity.id,
    );
    assert.equal(crossTenantLink.status, 400);
    const countAfter = await pool.query(`SELECT count(*)::int AS count FROM evidence_records WHERE organization_id = $1`, [fixture.organizationId]);
    assert.equal(countAfter.rows[0].count, countBefore.rows[0].count);
    assert.deepEqual((await fs.readdir(tenantBStorage).catch(() => [])).sort(), filesBefore.sort());

    const crossTenantDownload = await fetch(`${backend.baseUrl}/api/v1/evidence/${evidence.id}/download`, {
      headers: { Authorization: `Bearer ${tokenB}` },
    });
    assert.equal(crossTenantDownload.status, 404);

    const download = await fetch(`${backend.baseUrl}/api/v1/evidence/${evidence.id}/download`, {
      headers: { Authorization: `Bearer ${tokenA}` },
    });
    assert.equal(download.status, 200);
    assert.equal(await download.text(), '%PDF-1.7 task 2.4 evidence');

    const missingEvidence = await pool.query(
      `INSERT INTO evidence_records
         (organization_id,file_name,file_size_bytes,mime_type,sha256_hash,storage_path,uploaded_by)
       VALUES ($1,'missing.pdf',12,'application/pdf',$2,$3,$4) RETURNING id::text`,
      [fixture.organizationId, 'a'.repeat(64), path.join(workspace, 'vault_storage', fixture.organizationId, `missing-${suffix}.pdf`), fixture.userId],
    );
    const missingDownload = await fetch(`${backend.baseUrl}/api/v1/evidence/${missingEvidence.rows[0].id}/download`, {
      headers: { Authorization: `Bearer ${tokenA}` },
    });
    assert.equal(missingDownload.status, 404);
    assert.equal((await missingDownload.json()).error.code, 'EVIDENCE_FILE_NOT_FOUND');

    const statusUpdate = await jsonRequest(backend.baseUrl, '/calculations/run', tokenA, {
      method: 'POST',
      body: { activityDataId: activity.id },
    });
    assert.equal(statusUpdate.status, 200, await statusUpdate.clone().text());
    assert.equal((await findActivityData(fixture.organizationId, activity.id))?.status, 'CALCULATED');

    await backend.stop();
    backend = await startProductionBackend({
      TEST_USER_ID: fixture.userId,
      TEST_USER_EMAIL: email,
      TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: fixture.organizationId,
      TEST_ROLE: 'COMPANY_ADMIN',
    });
    const sessionAfterRestart = await login(backend.baseUrl, email, password, fixture.organizationId);
    const tokenAfterRestart = sessionAfterRestart.data.accessToken;
    const activityAfterRestart = await jsonRequest(backend.baseUrl, `/activity-data?facilityId=${fixture.facilityId}`, tokenAfterRestart);
    assert.equal(activityAfterRestart.status, 200);
    assert.equal((await activityAfterRestart.json()).data.some((item: any) => item.id === activity.id), true);
    const evidenceAfterRestart = await jsonRequest(backend.baseUrl, '/evidence', tokenAfterRestart);
    assert.equal((await evidenceAfterRestart.json()).data.some((item: any) => item.id === evidence.id), true);
    const downloadAfterRestart = await fetch(`${backend.baseUrl}/api/v1/evidence/${evidence.id}/download`, {
      headers: { Authorization: `Bearer ${tokenAfterRestart}` },
    });
    assert.equal(downloadAfterRestart.status, 200);
    assert.equal(await downloadAfterRestart.text(), '%PDF-1.7 task 2.4 evidence');
  } finally {
    await backend?.stop();
    for (const storagePath of storagePaths) {
      await fs.rm(storagePath, { force: true });
    }
    if (userIds.length) await pool.query('DELETE FROM users WHERE id = ANY($1::uuid[])', [userIds]);
    if (organizationIds.length) {
      await pool.query('DELETE FROM organizations WHERE id = ANY($1::uuid[])', [organizationIds]);
      for (const organizationId of organizationIds) {
        await fs.rm(path.join(workspace, 'vault_storage', organizationId), { recursive: true, force: true });
      }
    }
  }
});
