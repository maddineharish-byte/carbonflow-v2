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

test('API REFRESH 1: an expired access request refreshes once and retries with the new token', async () => {
  let refreshCalls = 0;
  let profileCalls = 0;

  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      const body = JSON.parse(String(init?.body));
      assert.equal(body.refreshToken, 'refresh-a');
      return makeResponse(200, {
        success: true,
        data: { accessToken: 'access-b', refreshToken: 'refresh-b' },
      });
    }

    assert.equal(url.endsWith('/auth/me'), true);
    profileCalls += 1;
    const authorization = new Headers(init?.headers).get('Authorization');
    if (profileCalls === 1) {
      assert.equal(authorization, 'Bearer access-a');
      return makeResponse(401, {
        success: false,
        error: { code: 'INVALID_TOKEN', message: 'Access token expired.' },
      });
    }

    assert.equal(authorization, 'Bearer access-b');
    return makeResponse(200, {
      success: true,
      data: { user: { id: 'user-1' }, organization: { id: 'org-1' }, role: 'REVIEWER' },
    });
  }) as typeof fetch;

  setAuthTokens('access-a', 'refresh-a');
  const profile = await api.getMe();

  assert.equal(profile.user.id, 'user-1');
  assert.equal(refreshCalls, 1);
  assert.equal(profileCalls, 2);
  assert.equal(getAccessToken(), 'access-b');
  assert.equal(getRefreshToken(), 'refresh-b');
});

test('API REFRESH 2: concurrent 401 responses share one refresh operation and retry each request', async () => {
  let refreshCalls = 0;
  let profileCalls = 0;
  let facilityCalls = 0;
  let unauthorizedCalls = 0;

  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      await new Promise((resolve) => setTimeout(resolve, 5));
      return makeResponse(200, {
        success: true,
        data: { accessToken: 'access-b', refreshToken: 'refresh-b' },
      });
    }

    if (url.endsWith('/auth/me')) {
      profileCalls += 1;
      return profileCalls === 1
        ? makeResponse(401, { success: false, error: { code: 'INVALID_TOKEN', message: 'Expired.' } })
        : makeResponse(200, { success: true, data: { user: { id: 'user-1' } } });
    }

    assert.equal(url.endsWith('/facilities'), true);
    facilityCalls += 1;
    return facilityCalls === 1
      ? makeResponse(401, { success: false, error: { code: 'INVALID_TOKEN', message: 'Expired.' } })
      : makeResponse(200, { success: true, data: [{ id: 'facility-1' }] });
  }) as typeof fetch;

  setUnauthorizedHandler(() => {
    unauthorizedCalls += 1;
  });
  setAuthTokens('access-a', 'refresh-a');

  const [profile, facilities] = await Promise.all([api.getMe(), api.getFacilities()]);

  assert.equal(profile.user.id, 'user-1');
  assert.equal(facilities[0].id, 'facility-1');
  assert.equal(refreshCalls, 1);
  assert.equal(profileCalls, 2);
  assert.equal(facilityCalls, 2);
  assert.equal(unauthorizedCalls, 0);
});

test('API REFRESH 3: a failed refresh clears the frontend session and does not retry repeatedly', async () => {
  let refreshCalls = 0;
  let profileCalls = 0;
  let unauthorizedCalls = 0;

  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      return makeResponse(401, {
        success: false,
        error: { code: 'REFRESH_TOKEN_REVOKED', message: 'Refresh token revoked.' },
      });
    }

    assert.equal(url.endsWith('/auth/me'), true);
    profileCalls += 1;
    return makeResponse(401, {
      success: false,
      error: { code: 'INVALID_TOKEN', message: 'Access token expired.' },
    });
  }) as typeof fetch;

  setUnauthorizedHandler(() => {
    unauthorizedCalls += 1;
    clearAuthTokens();
  });
  setAuthTokens('access-a', 'refresh-a');

  await assert.rejects(
    api.getMe(),
    (error: unknown) => error instanceof ApiError && error.status === 401
  );

  assert.equal(refreshCalls, 1);
  assert.equal(profileCalls, 1);
  assert.equal(unauthorizedCalls, 1);
  assert.equal(getAccessToken(), null);
  assert.equal(getRefreshToken(), null);
});

test('API REFRESH 4: authorization errors do not trigger token refresh', async () => {
  let refreshCalls = 0;
  let unauthorizedCalls = 0;

  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/auth/refresh')) {
      refreshCalls += 1;
      throw new Error('Refresh must not run for 403 responses.');
    }

    assert.equal(url.endsWith('/auth/me'), true);
    return makeResponse(403, {
      success: false,
      error: { code: 'FORBIDDEN', message: 'Insufficient permission.' },
    });
  }) as typeof fetch;

  setUnauthorizedHandler(() => {
    unauthorizedCalls += 1;
  });
  setAuthTokens('access-a', 'refresh-a');

  await assert.rejects(
    api.getMe(),
    (error: unknown) => error instanceof ApiError && error.status === 403
  );

  assert.equal(refreshCalls, 0);
  assert.equal(unauthorizedCalls, 0);
});
