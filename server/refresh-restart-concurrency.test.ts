import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import { hashRefreshToken } from './auth.ts';

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

interface BackendProcess {
  child: ChildProcess;
  baseUrl: string;
  stop: () => Promise<void>;
}

async function query<T = any>(text: string, values: unknown[] = []): Promise<T[]> {
  const result = await pool.query(text, values);
  return result.rows as T[];
}

async function startBackend(environment: Record<string, string>): Promise<BackendProcess> {
  const child = spawn(process.execPath, ['--import', 'tsx', backendScript], {
    cwd: workspace,
    env: { ...process.env, ...environment },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stderr = '';
  child.stderr!.on('data', (chunk) => { stderr += String(chunk); });
  const port = await new Promise<number>((resolve, reject) => {
    let stdout = '';
    const onData = (chunk: Buffer) => {
      stdout += String(chunk);
      const match = stdout.match(/READY:(\d+)/);
      if (match) resolve(Number(match[1]));
    };
    child.stdout!.on('data', onData);
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (!stdout.includes('READY:')) reject(new Error(`Backend exited before readiness (${code ?? signal ?? 'unknown'}): ${stderr}`));
    });
  });

  let stopped = false;
  const stop = async () => {
    if (stopped) return;
    stopped = true;
    if (child.exitCode !== null || child.signalCode !== null) return;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
        resolve();
      }, 3000);
      child.once('exit', () => { clearTimeout(timer); resolve(); });
      child.kill('SIGTERM');
    });
  };

  return { child, baseUrl: `http://127.0.0.1:${port}`, stop };
}

async function request(baseUrl: string, pathname: string, body: Record<string, unknown>) {
  return fetch(`${baseUrl}${pathname}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

async function tokenByRaw(rawRefreshToken: string) {
  const rows = await query(`
    SELECT rt.id::text, rt.user_id::text, rt.organization_id::text, rt.role_id::text,
           rt.family_id::text, rt.token_hash, rt.expires_at::text,
           rt.revoked_at::text, rt.replaced_by_token_id::text, rt.created_at::text
      FROM refresh_tokens rt
     WHERE rt.token_hash = $1
  `, [hashRefreshToken(rawRefreshToken)]);
  return rows[0];
}

function fixtureEnvironment() {
  const userId = `integration-user-${randomUUID()}`;
  const email = `${userId}@example.test`;
  const password = `integration-password-${randomUUID()}`;
  return {
    userId,
    email,
    password,
    env: {
      TEST_USER_ID: userId,
      TEST_USER_EMAIL: email,
      TEST_USER_PASSWORD: password,
      TEST_ORGANIZATION_ID: 'org-tenant-a-1111',
      TEST_ROLE: 'REVIEWER',
    },
  };
}

async function cleanupFixture(email: string): Promise<void> {
  await query('DELETE FROM users WHERE lower(email) = lower($1)', [email]);
}

after(async () => {
  await pool.end();
});

test('real backend process restart preserves a PostgreSQL-backed refresh token', async () => {
  const fixture = fixtureEnvironment();
  let backendA: BackendProcess | undefined;
  let backendB: BackendProcess | undefined;
  try {
    backendA = await startBackend(fixture.env);
    const login = await request(backendA.baseUrl, '/api/v1/auth/login', {
      email: fixture.email,
      password: fixture.password,
      organizationId: 'org-tenant-a-1111',
    });
    assert.equal(login.status, 200);
    const session = await login.json();
    const original = await tokenByRaw(session.data.refreshToken);
    assert.ok(original);
    assert.equal(original.token_hash, hashRefreshToken(session.data.refreshToken));

    await backendA.stop();
    backendA = undefined;
    backendB = await startBackend(fixture.env);

    const refreshed = await request(backendB.baseUrl, '/api/v1/auth/refresh', {
      refreshToken: session.data.refreshToken,
    });
    assert.equal(refreshed.status, 200);
    const rotated = await refreshed.json();
    const oldAfter = await tokenByRaw(session.data.refreshToken);
    const newRecord = await tokenByRaw(rotated.data.refreshToken);
    assert.ok(oldAfter?.revoked_at);
    assert.ok(newRecord);
    assert.equal(oldAfter.replaced_by_token_id, newRecord.id);
    assert.equal(newRecord.family_id, original.family_id);
    assert.equal(newRecord.organization_id, original.organization_id);
    assert.equal(newRecord.role_id, original.role_id);
  } finally {
    await backendB?.stop();
    await backendA?.stop();
    await cleanupFixture(fixture.email);
  }
});

test('separate backend processes allow exactly one concurrent rotation', async () => {
  const fixture = fixtureEnvironment();
  let backendA: BackendProcess | undefined;
  let backendB: BackendProcess | undefined;
  try {
    backendA = await startBackend(fixture.env);
    backendB = await startBackend(fixture.env);
    const login = await request(backendA.baseUrl, '/api/v1/auth/login', {
      email: fixture.email,
      password: fixture.password,
      organizationId: 'org-tenant-a-1111',
    });
    assert.equal(login.status, 200);
    const session = await login.json();
    const original = await tokenByRaw(session.data.refreshToken);
    assert.ok(original);

    const responses = await Promise.all([
      request(backendA.baseUrl, '/api/v1/auth/refresh', { refreshToken: session.data.refreshToken }),
      request(backendB.baseUrl, '/api/v1/auth/refresh', { refreshToken: session.data.refreshToken }),
    ]);
    const statuses = responses.map((response) => response.status).sort();
    assert.deepEqual(statuses, [200, 401]);
    const rejected = responses.find((response) => response.status === 401);
    assert.ok(rejected);
    assert.equal((await rejected.json()).error.code, 'REFRESH_TOKEN_REVOKED');

    const familyRows = await query(`
      SELECT id::text, revoked_at::text, replaced_by_token_id::text, token_hash
        FROM refresh_tokens
       WHERE family_id = $1
       ORDER BY created_at
    `, [original.family_id]);
    assert.equal(familyRows.length, 2);
    const oldAfter = familyRows.find((row) => row.id === original.id);
    const successor = familyRows.find((row) => row.id !== original.id);
    assert.ok(oldAfter?.revoked_at);
    assert.ok(successor);
    assert.equal(oldAfter.replaced_by_token_id, successor.id);
    assert.notEqual(successor.token_hash, session.data.refreshToken);
  } finally {
    await backendB?.stop();
    await backendA?.stop();
    await cleanupFixture(fixture.email);
  }
});
