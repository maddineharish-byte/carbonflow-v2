import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import bcrypt from 'bcryptjs';
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';

const { Pool } = pg;
const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME || 'carbonflow_dev',
  user: process.env.DB_USER || 'postgres',
  password: process.env.DB_PASSWORD,
});
const backendScript = fileURLToPath(new URL('./test-backend-process.ts', import.meta.url));
const workspace = path.dirname(path.dirname(backendScript));

async function startProductionBackend(environment: Record<string, string>) {
  const child = spawn(process.execPath, ['--import', 'tsx', backendScript], {
    cwd: workspace,
    env: { ...process.env, NODE_ENV: 'production', JWT_SECRET: 'integration-jwt-secret', REFRESH_TOKEN_SECRET: 'integration-refresh-secret', ...environment },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const port = await new Promise<number>((resolve, reject) => {
    let stdout = '';
    child.stdout!.on('data', (chunk) => {
      stdout += String(chunk);
      const match = stdout.match(/READY:(\d+)/);
      if (match) resolve(Number(match[1]));
    });
    child.once('error', reject);
    child.once('exit', (code) => { if (!stdout.includes('READY:')) reject(new Error(`Backend exited before readiness: ${code}`)); });
  });
  return {
    baseUrl: `http://127.0.0.1:${port}`,
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

async function request(baseUrl: string, pathname: string, body: Record<string, unknown>) {
  return fetch(`${baseUrl}${pathname}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}

after(async () => { await pool.end(); });

test('production authentication and tenant context use PostgreSQL identity records', async () => {
  const suffix = randomUUID();
  const email = `identity-${suffix}@example.test`;
  const password = `identity-password-${suffix}`;
  const taxId = `TEST-${suffix}`;
  const user = await pool.query(
    `INSERT INTO users (email, password_hash, full_name) VALUES ($1, $2, $3) RETURNING id::text`,
    [email, bcrypt.hashSync(password, 4), 'Identity Persistence Test'],
  );
  const organization = await pool.query(
    `INSERT INTO organizations (name, tax_id, country) VALUES ($1, $2, 'US') RETURNING id::text`,
    [`Identity Test Organization ${suffix}`, taxId],
  );
  const secondOrganization = await pool.query(
    `INSERT INTO organizations (name, tax_id, country) VALUES ($1, $2, 'US') RETURNING id::text`,
    [`Identity Second Organization ${suffix}`, `TEST2-${suffix}`],
  );
  const role = await pool.query(`SELECT id::text FROM roles WHERE name = 'COMPANY_ADMIN'`);
  const reviewerRole = await pool.query(`SELECT id::text FROM roles WHERE name = 'REVIEWER'`);
  await pool.query(
    `INSERT INTO organization_memberships (organization_id, user_id, role_id) VALUES ($1, $2, $3)`,
    [organization.rows[0].id, user.rows[0].id, role.rows[0].id],
  );
  await pool.query(
    `INSERT INTO organization_memberships (organization_id, user_id, role_id) VALUES ($1, $2, $3)`,
    [secondOrganization.rows[0].id, user.rows[0].id, reviewerRole.rows[0].id],
  );

  let backend: Awaited<ReturnType<typeof startProductionBackend>> | undefined;
  try {
    backend = await startProductionBackend({
      TEST_USER_ID: user.rows[0].id,
      TEST_USER_EMAIL: email,
      TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: organization.rows[0].id,
      TEST_ROLE: 'COMPANY_ADMIN',
    });
    const login = await request(backend.baseUrl, '/api/v1/auth/login', { email, password, organizationId: organization.rows[0].id });
    assert.equal(login.status, 200);
    const session = await login.json();
    assert.equal(session.data.user.id, user.rows[0].id);
    assert.equal(session.data.organization.id, organization.rows[0].id);
    assert.equal(session.data.role, 'COMPANY_ADMIN');

    const refreshed = await request(backend.baseUrl, '/api/v1/auth/refresh', { refreshToken: session.data.refreshToken });
    assert.equal(refreshed.status, 200);
    const refreshedSession = await refreshed.json();
    assert.equal(typeof refreshedSession.data.refreshToken, 'string');

    const me = await fetch(`${backend.baseUrl}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal(me.status, 200);
    const meBody = await me.json();
    assert.equal(meBody.data.user.id, user.rows[0].id);
    assert.equal(meBody.data.organization.id, organization.rows[0].id);

    const facilityCreate = await fetch(`${backend.baseUrl}/api/v1/facilities`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ name: 'Persisted Facility', facilityCode: `PERSISTED-${suffix}`, facilityType: 'OFFICE', country: 'US', gridRegion: 'TEST' }),
    });
    assert.equal(facilityCreate.status, 201);
    const facilityId = (await facilityCreate.json()).data.id;
    const duplicateFacility = await fetch(`${backend.baseUrl}/api/v1/facilities`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ name: 'Duplicate Facility', facilityCode: `PERSISTED-${suffix}`, facilityType: 'OFFICE', country: 'US', gridRegion: 'TEST' }),
    });
    const duplicateBody = await duplicateFacility.json();
    assert.equal(duplicateFacility.status, 409, JSON.stringify(duplicateBody));
    const invalidRelationship = await fetch(`${backend.baseUrl}/api/v1/facilities`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ name: 'Invalid Relationship', facilityCode: `INVALID-${suffix}`, country: 'US', gridRegion: 'TEST', legalEntityId: '00000000-0000-0000-0000-000000000000' }),
    });
    assert.equal(invalidRelationship.status, 400);
    const facilityRead = await fetch(`${backend.baseUrl}/api/v1/facilities`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal(facilityRead.status, 200);
    assert.equal((await facilityRead.json()).data.some((item: any) => item.id === facilityId), true);

    const periodCreate = await fetch(`${backend.baseUrl}/api/v1/reporting-periods`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ name: 'Persisted Period', startDate: '2025-01-01', endDate: '2025-12-31' }),
    });
    assert.equal(periodCreate.status, 201);
    const periodId = (await periodCreate.json()).data.id;
    const periodRead = await fetch(`${backend.baseUrl}/api/v1/reporting-periods`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal(periodRead.status, 200);
    assert.equal((await periodRead.json()).data.some((item: any) => item.id === periodId), true);

    await backend.stop();
    backend = await startProductionBackend({
      TEST_USER_ID: user.rows[0].id, TEST_USER_EMAIL: email, TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: organization.rows[0].id, TEST_ROLE: 'COMPANY_ADMIN',
    });
    const facilitiesAfterRestart = await fetch(`${backend.baseUrl}/api/v1/facilities`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal(facilitiesAfterRestart.status, 200);
    assert.equal((await facilitiesAfterRestart.json()).data.some((item: any) => item.id === facilityId), true);
    const foreignFacility = await pool.query(
      `INSERT INTO facilities (organization_id, name, facility_code, country, grid_region) VALUES ($1, 'Foreign Facility', $2, 'US', 'FOREIGN') RETURNING id::text`,
      [secondOrganization.rows[0].id, `FOREIGN-${suffix}`],
    );
    const tenantRead = await fetch(`${backend.baseUrl}/api/v1/facilities`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal((await tenantRead.json()).data.some((item: any) => item.id === foreignFacility.rows[0].id), false);

    const unauthorizedOrganization = await request(backend.baseUrl, '/api/v1/auth/login', { email, password, organizationId: '00000000-0000-0000-0000-000000000000' });
    assert.equal(unauthorizedOrganization.status, 403);

    const switched = await fetch(`${backend.baseUrl}/api/v1/auth/switch-tenant-or-role`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ targetOrgId: secondOrganization.rows[0].id, targetRole: 'REVIEWER' }),
    });
    assert.equal(switched.status, 200);
    const switchedSession = await switched.json();
    assert.equal(switchedSession.data.organization.id, secondOrganization.rows[0].id);
    assert.equal(switchedSession.data.role, 'REVIEWER');

    await pool.query('UPDATE organization_memberships SET is_active = FALSE WHERE organization_id = $1 AND user_id = $2', [secondOrganization.rows[0].id, user.rows[0].id]);
    const inactiveMembershipSwitch = await fetch(`${backend.baseUrl}/api/v1/auth/switch-tenant-or-role`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${session.data.accessToken}` },
      body: JSON.stringify({ targetOrgId: secondOrganization.rows[0].id, targetRole: 'REVIEWER' }),
    });
    assert.equal(inactiveMembershipSwitch.status, 403);

    await pool.query('UPDATE users SET is_active = FALSE WHERE id = $1', [user.rows[0].id]);
    const inactiveUser = await fetch(`${backend.baseUrl}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${session.data.accessToken}` } });
    assert.equal(inactiveUser.status, 401);
  } finally {
    await backend?.stop();
    await pool.query('DELETE FROM users WHERE id = $1', [user.rows[0].id]);
    await pool.query('DELETE FROM organizations WHERE id = ANY($1::uuid[])', [[organization.rows[0].id, secondOrganization.rows[0].id]]);
  }
});
