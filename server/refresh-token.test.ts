import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import express from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { apiRouter } from './routes.ts';
import { hashRefreshToken } from './auth.ts';
import { db } from './db.ts';
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import type { OrganizationMembership, RoleName, User } from './types.ts';

const { Pool } = pg;
const testPool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.DB_NAME || 'carbonflow_dev',
  user: process.env.DB_USER || 'postgres',
  password: process.env.DB_PASSWORD,
});

async function query<T = any>(text: string, values: unknown[] = []): Promise<T[]> {
  const result = await testPool.query(text, values);
  return result.rows as T[];
}

async function tokenByRaw(rawRefreshToken: string) {
  const rows = await query(`
    SELECT rt.id::text, rt.user_id::text, rt.organization_id::text, rt.role_id::text,
           rt.family_id::text, rt.token_hash, rt.expires_at::text,
           rt.revoked_at::text, rt.replaced_by_token_id::text, rt.created_at::text,
           u.email, o.tax_id, r.name AS role_name,
           EXISTS (
             SELECT 1 FROM organization_memberships om
             WHERE om.organization_id = rt.organization_id
               AND om.user_id = rt.user_id
               AND om.role_id = rt.role_id
               AND om.is_active = TRUE
           ) AS membership_active
      FROM refresh_tokens rt
      JOIN users u ON u.id = rt.user_id
      JOIN organizations o ON o.id = rt.organization_id
      JOIN roles r ON r.id = rt.role_id
     WHERE rt.token_hash = $1
  `, [hashRefreshToken(rawRefreshToken)]);
  return rows[0];
}

async function setExpiredByRaw(rawRefreshToken: string): Promise<void> {
  await query(
    `UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
      WHERE token_hash = $1`,
    [hashRefreshToken(rawRefreshToken)],
  );
}

let server: ReturnType<express.Application['listen']>;
let baseUrl: string;

const JWT_SECRET = process.env.JWT_SECRET || 'carbonflow-jwt-super-secret-key-2026-sha256';

before(async () => {
  const app = express();
  app.use(express.json({ limit: '1mb' }));
  app.use('/api/v1', apiRouter);

  await new Promise<void>((resolve) => {
    server = app.listen(0, '127.0.0.1', () => resolve());
  });

  const address = server.address() as AddressInfo;
  baseUrl = `http://127.0.0.1:${address.port}`;
});

after(async () => {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
  await testPool.end();
});

interface FixtureMembership {
  organizationId: string;
  role: RoleName;
  isActive?: boolean;
}

interface Fixture {
  user: User;
  password: string;
  cleanup: () => Promise<void>;
}

function createFixture(memberships: FixtureMembership[], userIsActive = true): Fixture {
  const userId = `refresh-user-${randomUUID()}`;
  const password = `refresh-password-${randomUUID()}`;
  const user: User = {
    id: userId,
    email: `${userId}@example.test`,
    passwordHash: bcrypt.hashSync(password, 4),
    fullName: 'TASK-003 Refresh User',
    isActive: userIsActive,
    createdAt: new Date().toISOString(),
  };

  db.users.push(user);
  for (const membership of memberships) {
    const record: OrganizationMembership = {
      id: `refresh-membership-${randomUUID()}`,
      organizationId: membership.organizationId,
      userId,
      role: membership.role,
      isActive: membership.isActive ?? true,
      createdAt: new Date().toISOString(),
    };
    db.memberships.push(record);
  }

  return {
    user,
    password,
    cleanup: async () => {
      for (let index = db.memberships.length - 1; index >= 0; index -= 1) {
        if (db.memberships[index].userId === userId) db.memberships.splice(index, 1);
      }
      for (let index = db.users.length - 1; index >= 0; index -= 1) {
        if (db.users[index].id === userId) db.users.splice(index, 1);
      }
      await query('DELETE FROM users WHERE lower(email) = lower($1)', [user.email]);
    },
  };
}

interface HttpResult {
  status: number;
  body: any;
}

async function request(pathname: string, init: RequestInit = {}): Promise<HttpResult> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${baseUrl}${pathname}`, { ...init, headers });
  const text = await response.text();
  let body: any;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }
  return { status: response.status, body };
}

async function login(fixture: Fixture, organizationId?: string) {
  const result = await request('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({
      email: fixture.user.email,
      password: fixture.password,
      organizationId,
    }),
  });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  return result.body.data;
}

async function refresh(refreshToken: string, extra: Record<string, unknown> = {}) {
  return request('/api/v1/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken, ...extra }),
  });
}

function authHeaders(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

test('REFRESH TEST 1: login returns an access/refresh pair and stores only a hash', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    assert.equal(typeof session.accessToken, 'string');
    assert.equal(typeof session.refreshToken, 'string');
    assert.notEqual(session.accessToken, session.refreshToken);

    const record = await tokenByRaw(session.refreshToken);
    assert.ok(record);
    assert.equal(record.email, fixture.user.email);
    assert.equal(record.tax_id, 'US-94-3829104');
    assert.equal(record.role_name, 'COMPANY_ADMIN');
    assert.ok(record.family_id);
    assert.notEqual(record.token_hash, session.refreshToken);
    assert.equal(record.token_hash, hashRefreshToken(session.refreshToken));
    assert.ok(Date.parse(record.expires_at) > Date.now());
    assert.equal(record.revoked_at, null);
    assert.equal(record.replaced_by_token_id, null);
    assert.equal(record.membership_active, true);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 2: a valid refresh token returns a new access/refresh pair', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await refresh(session.refreshToken);

    assert.equal(result.status, 200, JSON.stringify(result.body));
    assert.equal(typeof result.body.data.accessToken, 'string');
    assert.equal(typeof result.body.data.refreshToken, 'string');
    assert.notEqual(result.body.data.refreshToken, session.refreshToken);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 3: successful refresh rotates and revokes the old token', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const oldBefore = await tokenByRaw(session.refreshToken);
    assert.ok(oldBefore);

    const result = await refresh(session.refreshToken);
    assert.equal(result.status, 200, JSON.stringify(result.body));
    const oldAfter = await tokenByRaw(session.refreshToken);
    const newRecord = await tokenByRaw(result.body.data.refreshToken);
    assert.ok(oldAfter);
    assert.ok(newRecord);

    assert.ok(oldAfter.revoked_at);
    assert.equal(oldAfter.replaced_by_token_id, newRecord.id);
    assert.equal(newRecord.family_id, oldBefore.family_id);
    assert.equal(newRecord.organization_id, oldBefore.organization_id);
    assert.equal(newRecord.role_id, oldBefore.role_id);
    assert.equal(newRecord.revoked_at, null);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 4: an old rotated refresh token cannot be reused', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const firstRefresh = await refresh(session.refreshToken);
    assert.equal(firstRefresh.status, 200);

    const replay = await refresh(session.refreshToken);
    assert.equal(replay.status, 401);
    assert.equal(replay.body.error.code, 'REFRESH_TOKEN_REVOKED');
    assert.equal(replay.body.data, undefined);

    const newTokenResult = await refresh(firstRefresh.body.data.refreshToken);
    assert.equal(newTokenResult.status, 200);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 5: an expired refresh token is rejected', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    assert.ok(await tokenByRaw(session.refreshToken));
    await setExpiredByRaw(session.refreshToken);

    const result = await refresh(session.refreshToken);
    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'REFRESH_TOKEN_EXPIRED');
    assert.equal(result.body.data, undefined);
    const record = await tokenByRaw(session.refreshToken);
    assert.ok(record?.revoked_at);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 6: an unknown refresh token is rejected', async () => {
  const result = await refresh(randomBytes(32).toString('hex'));

  assert.equal(result.status, 401);
  assert.equal(result.body.error.code, 'INVALID_REFRESH_TOKEN');
  assert.equal(result.body.data, undefined);
});

test('REFRESH TEST 7: an inactive user cannot refresh', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    fixture.user.isActive = false;

    const result = await refresh(session.refreshToken);
    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'USER_DEACTIVATED');
    assert.equal(result.body.data, undefined);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 8: refresh cannot change the organization context', async () => {
  const fixture = createFixture([
    { organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' },
    { organizationId: 'org-tenant-b-2222', role: 'COMPANY_ADMIN' },
  ]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const originalRecord = await tokenByRaw(session.refreshToken);
    assert.ok(originalRecord);

    const result = await refresh(session.refreshToken, {
      organizationId: 'org-tenant-b-2222',
      targetOrgId: 'org-tenant-b-2222',
    });
    assert.equal(result.status, 400);
    assert.equal(result.body.error.code, 'VALIDATION_ERROR');
    const afterRejectedOverride = await tokenByRaw(session.refreshToken);
    assert.equal(afterRejectedOverride?.revoked_at, null);

    const validRefresh = await refresh(session.refreshToken);
    assert.equal(validRefresh.status, 200);
    const rotatedRecord = await tokenByRaw(validRefresh.body.data.refreshToken);
    assert.equal(rotatedRecord?.tax_id, 'US-94-3829104');
    assert.equal(rotatedRecord?.role_name, 'COMPANY_ADMIN');
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 9: refresh cannot change the role context', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const originalRecord = await tokenByRaw(session.refreshToken);
    assert.ok(originalRecord);

    const result = await refresh(session.refreshToken, { targetRole: 'COMPANY_ADMIN' });
    assert.equal(result.status, 400);
    assert.equal(result.body.error.code, 'VALIDATION_ERROR');
    const afterRejectedOverride = await tokenByRaw(session.refreshToken);
    assert.equal(afterRejectedOverride?.role_name, 'REVIEWER');
    assert.equal(afterRejectedOverride?.revoked_at, null);

    const validRefresh = await refresh(session.refreshToken);
    assert.equal(validRefresh.status, 200);
    const rotatedRecord = await tokenByRaw(validRefresh.body.data.refreshToken);
    assert.equal(rotatedRecord?.role_name, 'REVIEWER');
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 10: logout revokes the refresh session', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const logout = await request('/api/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    });
    assert.equal(logout.status, 200, JSON.stringify(logout.body));
    assert.equal(logout.body.data.revoked, true);

    const result = await refresh(session.refreshToken);
    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'REFRESH_TOKEN_REVOKED');
    assert.equal(result.body.data, undefined);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 11: an expired access token can be replaced through refresh and retried', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const expiredAccessToken = jwt.sign(
      {
        userId: fixture.user.id,
        organizationId: 'org-tenant-a-1111',
        role: 'REVIEWER',
      },
      JWT_SECRET,
      { expiresIn: -1 }
    );

    const protectedBeforeRefresh = await request('/api/v1/facilities', {
      headers: authHeaders(expiredAccessToken),
    });
    assert.equal(protectedBeforeRefresh.status, 401);

    const refreshed = await refresh(session.refreshToken);
    assert.equal(refreshed.status, 200, JSON.stringify(refreshed.body));

    const protectedAfterRefresh = await request('/api/v1/facilities', {
      headers: authHeaders(refreshed.body.data.accessToken),
    });
    assert.equal(protectedAfterRefresh.status, 200);
    assert.ok(protectedAfterRefresh.body.data.every((facility: any) => facility.organizationId === 'org-tenant-a-1111'));
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 12: an inactive membership cannot refresh', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const membership = db.memberships.find((candidate) => candidate.userId === fixture.user.id);
    assert.ok(membership);
    membership.isActive = false;

    const result = await refresh(session.refreshToken);
    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'REFRESH_MEMBERSHIP_INVALID');
    assert.equal(result.body.data, undefined);
  } finally {
    await fixture.cleanup();
  }
});

test('REFRESH TEST 13: concurrent use of one refresh token permits only one rotation', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const results = await Promise.all([
      refresh(session.refreshToken),
      refresh(session.refreshToken),
    ]);
    const statuses = results.map((result) => result.status).sort();
    assert.deepEqual(statuses, [200, 401]);
    assert.equal(results.find((result) => result.status === 200)?.body.data.refreshToken !== undefined, true);
    assert.equal(results.find((result) => result.status === 401)?.body.data, undefined);
  } finally {
    await fixture.cleanup();
  }
});
