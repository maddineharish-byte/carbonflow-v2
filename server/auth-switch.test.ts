import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import express from 'express';
import bcrypt from 'bcryptjs';
import { apiRouter } from './routes.ts';
import { db } from './db.ts';
import type { OrganizationMembership, RoleName, User } from './types.ts';

let server: ReturnType<express.Application['listen']>;
let baseUrl: string;

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
});

interface FixtureMembership {
  organizationId: string;
  role: RoleName;
  isActive?: boolean;
}

interface Fixture {
  user: User;
  password: string;
  cleanup: () => void;
}

function createFixture(memberships: FixtureMembership[], userIsActive = true): Fixture {
  const userId = `test-user-${randomUUID()}`;
  const password = `test-password-${randomUUID()}`;
  const user: User = {
    id: userId,
    email: `${userId}@example.test`,
    passwordHash: bcrypt.hashSync(password, 4),
    fullName: 'TASK-001 Test User',
    isActive: userIsActive,
    createdAt: new Date().toISOString(),
  };

  db.users.push(user);
  for (const membership of memberships) {
    const record: OrganizationMembership = {
      id: `test-membership-${randomUUID()}`,
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
    cleanup: () => {
      for (let index = db.memberships.length - 1; index >= 0; index -= 1) {
        if (db.memberships[index].userId === userId) db.memberships.splice(index, 1);
      }
      for (let index = db.users.length - 1; index >= 0; index -= 1) {
        if (db.users[index].id === userId) db.users.splice(index, 1);
      }
    },
  };
}

interface HttpResult {
  status: number;
  contentType: string | null;
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
  return {
    status: response.status,
    contentType: response.headers.get('content-type'),
    body,
  };
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

function authHeaders(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

function assertNoToken(result: HttpResult) {
  assert.equal(result.body?.success, false);
  assert.equal(result.body?.data, undefined);
}

test('TEST 1: unauthenticated switch request is rejected', async () => {
  const result = await request('/api/v1/auth/switch-tenant-or-role', {
    method: 'POST',
    body: JSON.stringify({ targetOrgId: 'org-tenant-b-2222', targetRole: 'COMPANY_ADMIN' }),
  });

  assert.equal(result.status, 401);
  assert.equal(result.body.success, false);
  assert.equal(result.body.error.code, 'UNAUTHORIZED');
  assertNoToken(result);
});

test('TEST 2: authenticated user can switch to an assigned organization and role', async () => {
  const fixture = createFixture([
    { organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' },
    { organizationId: 'org-tenant-b-2222', role: 'COMPANY_ADMIN' },
  ]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-b-2222', targetRole: 'COMPANY_ADMIN' }),
    });

    assert.equal(result.status, 200, JSON.stringify(result.body));
    assert.equal(result.body.success, true);
    assert.equal(result.body.data.organization.id, 'org-tenant-b-2222');
    assert.equal(result.body.data.role, 'COMPANY_ADMIN');
    assert.ok(result.body.data.accessToken);
    assert.equal(result.body.data.user.passwordHash, undefined);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 3: authenticated user cannot switch to an unassigned organization', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-b-2222', targetRole: 'REVIEWER' }),
    });

    assert.equal(result.status, 403);
    assert.equal(result.body.error.code, 'SWITCH_NOT_AUTHORIZED');
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 4: authenticated user cannot select a role not assigned to the membership', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-a-1111', targetRole: 'COMPANY_ADMIN' }),
    });

    assert.equal(result.status, 403);
    assert.equal(result.body.error.code, 'SWITCH_NOT_AUTHORIZED');
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 5: switch endpoint cannot create or mutate a membership', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const targetUserId = 'user-acme-admin-1';
    const before = db.memberships
      .filter((membership) => membership.userId === targetUserId)
      .map((membership) => ({
        organizationId: membership.organizationId,
        role: membership.role,
        isActive: membership.isActive,
      }));
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({
        targetOrgId: 'org-tenant-b-2222',
        targetRole: 'COMPANY_ADMIN',
        userId: targetUserId,
      }),
    });
    const after = db.memberships
      .filter((membership) => membership.userId === targetUserId)
      .map((membership) => ({
        organizationId: membership.organizationId,
        role: membership.role,
        isActive: membership.isActive,
      }));

    assert.equal(result.status, 403);
    assert.equal(result.body.error.code, 'SWITCH_NOT_AUTHORIZED');
    assert.deepEqual(after, before);
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 6: COMPANY_ADMIN cannot be obtained by request-body manipulation', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-a-1111', targetRole: 'COMPANY_ADMIN' }),
    });

    assert.equal(result.status, 403);
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 7: PLATFORM_ADMIN cannot be self-selected through tenant switching', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-a-1111', targetRole: 'PLATFORM_ADMIN' }),
    });

    assert.equal(result.status, 403);
    assert.equal(result.body.error.code, 'ROLE_NOT_SWITCHABLE');
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 8: inactive membership cannot be selected', async () => {
  const fixture = createFixture([
    { organizationId: 'org-tenant-a-1111', role: 'REVIEWER' },
    { organizationId: 'org-tenant-b-2222', role: 'REVIEWER', isActive: false },
  ]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-b-2222', targetRole: 'REVIEWER' }),
    });

    assert.equal(result.status, 403);
    assert.equal(result.body.error.code, 'SWITCH_NOT_AUTHORIZED');
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 9: inactive user cannot switch', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    fixture.user.isActive = false;
    const result = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-a-1111', targetRole: 'REVIEWER' }),
    });

    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'USER_DEACTIVATED');
    assertNoToken(result);
  } finally {
    fixture.cleanup();
  }
});

test('TEST 10: a legitimate switch token is restricted to the authorized organization', async () => {
  const fixture = createFixture([
    { organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' },
    { organizationId: 'org-tenant-b-2222', role: 'COMPANY_ADMIN' },
  ]);

  try {
    const session = await login(fixture, 'org-tenant-a-1111');
    const switched = await request('/api/v1/auth/switch-tenant-or-role', {
      method: 'POST',
      headers: authHeaders(session.accessToken),
      body: JSON.stringify({ targetOrgId: 'org-tenant-b-2222', targetRole: 'COMPANY_ADMIN' }),
    });
    assert.equal(switched.status, 200, JSON.stringify(switched.body));

    const facilities = await request('/api/v1/facilities', {
      headers: authHeaders(switched.body.data.accessToken),
    });
    assert.equal(facilities.status, 200);
    assert.equal(facilities.body.data.length, 1);
    assert.equal(facilities.body.data[0].organizationId, 'org-tenant-b-2222');

    const crossTenantAudit = await request('/api/v1/audits/audit-acme-fy2024', {
      headers: authHeaders(switched.body.data.accessToken),
    });
    assert.equal(crossTenantAudit.status, 404);
  } finally {
    fixture.cleanup();
  }
});
