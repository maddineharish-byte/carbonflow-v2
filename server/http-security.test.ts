import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import type { AddressInfo } from 'node:net';
import { corsPolicy, createRateLimiter, safeErrorHandler, securityHeaders } from './http-security.ts';

let server: ReturnType<express.Application['listen']>;
let baseUrl: string;
let tick = 0;

before(async () => {
  const app = express();
  app.disable('x-powered-by');
  app.use(securityHeaders);
  app.use(corsPolicy);
  app.use(createRateLimiter({ windowMs: 60_000, maxRequests: 1, now: () => tick }));
  app.get('/health', (_req, res) => res.json({ success: true }));
  app.get('/bad-json', (_req, _res, next) => next(Object.assign(new SyntaxError('bad json'), { body: true })));
  app.use(safeErrorHandler);
  await new Promise<void>((resolve) => { server = app.listen(0, '127.0.0.1', () => resolve()); });
  baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

after(async () => {
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
});

test('security middleware sets baseline headers', async () => {
  const response = await fetch(`${baseUrl}/health`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(response.headers.get('x-frame-options'), 'DENY');
  assert.equal(response.headers.get('x-powered-by'), null);
});

test('safe error handler returns a non-sensitive JSON error', async () => {
  tick = 200000;
  const response = await fetch(`${baseUrl}/bad-json`);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.code, 'INVALID_JSON');
});

test('configured CORS origin is explicit and preflight is bounded', async () => {
  process.env.CORS_ORIGINS = 'https://carbonflow.example.test';
  const response = await fetch(`${baseUrl}/health`, { method: 'OPTIONS', headers: { Origin: 'https://carbonflow.example.test' } });
  assert.equal(response.status, 204);
  assert.equal(response.headers.get('access-control-allow-origin'), 'https://carbonflow.example.test');
  assert.equal(response.headers.get('access-control-allow-credentials'), 'true');
  delete process.env.CORS_ORIGINS;
});

test('rate limiter rejects requests over the configured window', async () => {
  tick = 300000;
  const first = await fetch(`${baseUrl}/health`);
  const second = await fetch(`${baseUrl}/health`);
  assert.equal(first.status, 200);
  assert.equal(second.status, 429);
  assert.equal((await second.json()).error.code, 'RATE_LIMITED');
});
