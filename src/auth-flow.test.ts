import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  api,
  ApiError,
  clearAuthTokens,
  getAccessToken,
  getRefreshToken,
  setAuthTokens,
  setUnauthorizedHandler,
} from './services/api.ts';

const originalFetch = globalThis.fetch;

function makeResponse(status: number, body: any): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

afterEach(() => {
  globalThis.fetch = originalFetch;
  setUnauthorizedHandler(null);
  clearAuthTokens();
});

test('AUTH FLOW 1: login success stores the token pair and returns the session', async () => {
  globalThis.fetch = (async (_input: RequestInfo | URL, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body));
    assert.equal(body.email, 'admin@acmeglobal.com');
    assert.equal(body.password, 'secret');
    return makeResponse(200, {
      success: true,
      data: {
        user: { id: 'user-1', email: 'admin@acmeglobal.com', fullName: 'Admin' },
        organization: { id: 'org-1', name: 'Acme' },
        role: 'COMPANY_ADMIN',
        permissions: ['facilities.read'],
        memberships: [{ organizationId: 'org-1', organizationName: 'Acme', role: 'COMPANY_ADMIN' }],
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
      },
    });
  }) as typeof fetch;

  const session = await api.login('admin@acmeglobal.com', 'secret');

  // App.tsx owns token storage: it persists the pair returned by login.
  setAuthTokens(session.accessToken, session.refreshToken);

  assert.equal(session.accessToken, 'access-1');
  assert.equal(session.refreshToken, 'refresh-1');
  assert.equal(getAccessToken(), 'access-1');
  assert.equal(getRefreshToken(), 'refresh-1');
  assert.equal(session.user.email, 'admin@acmeglobal.com');
  assert.equal(session.role, 'COMPANY_ADMIN');
  assert.equal(session.memberships.length, 1);
});

test('AUTH FLOW 2: login failure surfaces the backend error and stores no tokens', async () => {
  globalThis.fetch = (async () =>
    makeResponse(401, {
      success: false,
      error: { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password.' },
    })) as typeof fetch;

  await assert.rejects(
    api.login('admin@acmeglobal.com', 'wrong'),
    (error: unknown) => error instanceof ApiError && error.status === 401
  );

  assert.equal(getAccessToken(), null);
  assert.equal(getRefreshToken(), null);
});

test('AUTH FLOW 3: an expired session refreshes once and retries the original request', async () => {
  let refreshCalls = 0;
  let meCalls = 0;

  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      return makeResponse(200, {
        success: true,
        data: { accessToken: 'access-new', refreshToken: 'refresh-new' },
      });
    }
    assert.equal(url.endsWith('/auth/me'), true);
    meCalls += 1;
    if (meCalls === 1) {
      return makeResponse(401, {
        success: false,
        error: { code: 'INVALID_TOKEN', message: 'Access token expired.' },
      });
    }
    const authorization = new Headers(init?.headers).get('Authorization');
    assert.equal(authorization, 'Bearer access-new');
    return makeResponse(200, {
      success: true,
      data: { user: { id: 'user-1' }, organization: { id: 'org-1' }, role: 'REVIEWER' },
    });
  }) as typeof fetch;

  setAuthTokens('access-old', 'refresh-old');
  const profile = await api.getMe();

  assert.equal(profile.user.id, 'user-1');
  assert.equal(refreshCalls, 1);
  assert.equal(meCalls, 2);
  assert.equal(getAccessToken(), 'access-new');
  assert.equal(getRefreshToken(), 'refresh-new');
});

test('AUTH FLOW 4: logout revokes the refresh token and clears the local session', async () => {
  let logoutBody: any = null;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/auth/logout')) {
      logoutBody = JSON.parse(String(init?.body));
      return makeResponse(200, { success: true, data: { revoked: true } });
    }
    return makeResponse(200, { success: true, data: {} });
  }) as typeof fetch;

  setAuthTokens('access-1', 'refresh-1');
  const result = await api.logout('refresh-1');

  assert.equal(result.revoked, true);
  assert.equal(logoutBody.refreshToken, 'refresh-1');
  // App.tsx clears local session state after the revoke call.
  clearAuthTokens();
  assert.equal(getAccessToken(), null);
  assert.equal(getRefreshToken(), null);
});

test('AUTH FLOW 5: tenant loading returns the backend session with memberships and permissions', async () => {
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    assert.equal(url.endsWith('/auth/me'), true);
    const authorization = new Headers(init?.headers).get('Authorization');
    assert.equal(authorization, 'Bearer access-1');
    return makeResponse(200, {
      success: true,
      data: {
        user: { id: 'user-9', email: 'owner@acmeglobal.com', fullName: 'Owner' },
        organization: { id: 'org-9', name: 'Acme' },
        role: 'DATA_OWNER',
        permissions: ['analytics.read', 'targets.read'],
        memberships: [
          { organizationId: 'org-9', organizationName: 'Acme', role: 'DATA_OWNER' },
          { organizationId: 'org-10', organizationName: 'Apex', role: 'REVIEWER' },
        ],
      },
    });
  }) as typeof fetch;

  setAuthTokens('access-1', 'refresh-1');
  const profile = await api.getMe();

  assert.equal(profile.organization.id, 'org-9');
  assert.equal(profile.role, 'DATA_OWNER');
  assert.deepEqual(profile.permissions, ['analytics.read', 'targets.read']);
  assert.equal(profile.memberships.length, 2);
  assert.equal(profile.memberships[1].organizationName, 'Apex');
});

test('AUTH FLOW 6: a deactivated user is refused with USER_DEACTIVATED and triggers the 401 boundary', async () => {
  let unauthorizedCalls = 0;
  setUnauthorizedHandler(() => {
    unauthorizedCalls += 1;
  });

  globalThis.fetch = (async () =>
    makeResponse(401, {
      success: false,
      error: { code: 'USER_DEACTIVATED', message: 'User account is inactive or deleted.' },
    })) as typeof fetch;

  setAuthTokens('access-1', 'refresh-1');
  await assert.rejects(
    api.getFacilities(),
    (error: unknown) => error instanceof ApiError && error.code === 'USER_DEACTIVATED'
  );

  assert.equal(unauthorizedCalls, 1);
});
