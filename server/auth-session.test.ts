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
  const userId = `session-user-${randomUUID()}`;
  const password = `session-password-${randomUUID()}`;
  const user: User = {
    id: userId,
    email: `${userId}@example.test`,
    passwordHash: bcrypt.hashSync(password, 4),
    fullName: 'TASK-002 Session User',
    isActive: userIsActive,
    createdAt: new Date().toISOString(),
  };

  db.users.push(user);
  for (const membership of memberships) {
    const record: OrganizationMembership = {
      id: `session-membership-${randomUUID()}`,
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

function authHeaders(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

test('SESSION 1: fresh session cannot call the authenticated profile endpoint', async () => {
  const result = await request('/api/v1/auth/me');

  assert.equal(result.status, 401);
  assert.equal(result.body.success, false);
  assert.equal(result.body.error.code, 'UNAUTHORIZED');
  assert.equal(result.body.data, undefined);
});

test('SESSION 2: valid login creates a session that /auth/me accepts', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' }]);

  try {
    const loginData = await login(fixture, 'org-tenant-a-1111');
    assert.ok(loginData.accessToken);
    assert.equal(loginData.user.passwordHash, undefined);

    const profile = await request('/api/v1/auth/me', {
      headers: authHeaders(loginData.accessToken),
    });

    assert.equal(profile.status, 200, JSON.stringify(profile.body));
    assert.equal(profile.body.data.user.id, fixture.user.id);
    assert.equal(profile.body.data.organization.id, 'org-tenant-a-1111');
    assert.equal(profile.body.data.role, 'COMPANY_ADMIN');
    assert.equal(profile.body.data.user.passwordHash, undefined);
  } finally {
    fixture.cleanup();
  }
});

test('SESSION 3: invalid credentials do not create a session', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const result = await request('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: fixture.user.email, password: 'wrong-password' }),
    });

    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'INVALID_CREDENTIALS');
    assert.equal(result.body.data, undefined);
  } finally {
    fixture.cleanup();
  }
});

test('SESSION 4: an invalid stored token is rejected by the backend', async () => {
  const result = await request('/api/v1/auth/me', {
    headers: authHeaders('not-a-valid-token'),
  });

  assert.equal(result.status, 401);
  assert.equal(result.body.error.code, 'INVALID_TOKEN');
  assert.equal(result.body.data, undefined);
});

test('SESSION 5: protected endpoints reject requests without authentication', async () => {
  const result = await request('/api/v1/facilities');

  assert.equal(result.status, 401);
  assert.equal(result.body.error.code, 'UNAUTHORIZED');
  assert.equal(result.body.data, undefined);
});

test('SESSION 6: re-login returns a fresh accepted session for the same user', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'REVIEWER' }]);

  try {
    const firstLogin = await login(fixture, 'org-tenant-a-1111');
    const firstProfile = await request('/api/v1/auth/me', {
      headers: authHeaders(firstLogin.accessToken),
    });
    assert.equal(firstProfile.status, 200);

    const secondLogin = await login(fixture, 'org-tenant-a-1111');
    const secondProfile = await request('/api/v1/auth/me', {
      headers: authHeaders(secondLogin.accessToken),
    });

    assert.equal(secondProfile.status, 200);
    assert.equal(secondProfile.body.data.user.id, fixture.user.id);
    assert.ok(secondLogin.accessToken);
  } finally {
    fixture.cleanup();
  }
});

test('SESSION 7: a legitimate login can still use TASK-001 membership switching', async () => {
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
    assert.equal(switched.body.data.organization.id, 'org-tenant-b-2222');
    assert.equal(switched.body.data.role, 'COMPANY_ADMIN');
  } finally {
    fixture.cleanup();
  }
});

test('SESSION 9: inactive users cannot log in', async () => {
  const fixture = createFixture([{ organizationId: 'org-tenant-a-1111', role: 'COMPANY_ADMIN' }], false);
  try {
    const result = await request('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: fixture.user.email, password: fixture.password }),
    });
    assert.equal(result.status, 401);
    assert.equal(result.body.error.code, 'USER_DEACTIVATED');
    assert.equal(result.body.data, undefined);
  } finally {
    fixture.cleanup();
  }
});

test('SESSION 10: production runtime test-suite endpoint is not exposed', async () => {
  const result = await request('/api/v1/test-suite/run');
  assert.equal(result.status, 404);
});

test('SESSION 8: frontend token-pair storage can be cleared for logout', async () => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  };
  Object.defineProperty(globalThis, 'window', {
    value: { localStorage: storage },
    configurable: true,
  });

  const apiService = await import('../src/services/api.ts');
  apiService.setAuthTokens('temporary-test-access', 'temporary-test-refresh');
  assert.equal(apiService.getAccessToken(), 'temporary-test-access');
  assert.equal(apiService.getRefreshToken(), 'temporary-test-refresh');
  assert.equal(values.get('cf_access_token'), 'temporary-test-access');
  assert.equal(values.get('cf_refresh_token'), 'temporary-test-refresh');

  apiService.clearAuthTokens();
  assert.equal(apiService.getAccessToken(), null);
  assert.equal(apiService.getRefreshToken(), null);
  assert.equal(values.has('cf_access_token'), false);
  assert.equal(values.has('cf_refresh_token'), false);
});
