import test from 'node:test';
import assert from 'node:assert/strict';
import { assertProductionConfiguration, inspectProductionConfiguration } from './config.ts';

test('production configuration fails closed without required values', () => {
  const result = inspectProductionConfiguration({ NODE_ENV: 'production' });
  assert.equal(result.production, true);
  assert.deepEqual(result.missing, ['DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD', 'JWT_SECRET', 'REFRESH_TOKEN_SECRET']);
  assert.throws(() => assertProductionConfiguration({ NODE_ENV: 'production' }), /Production configuration is incomplete/);
});

test('development configuration preserves non-production ergonomics', () => {
  const result = inspectProductionConfiguration({ NODE_ENV: 'development' });
  assert.deepEqual(result, { production: false, missing: [] });
  assert.doesNotThrow(() => assertProductionConfiguration({ NODE_ENV: 'development' }));
});

test('production configuration accepts complete variable names', () => {
  assert.doesNotThrow(() => assertProductionConfiguration({
    NODE_ENV: 'production',
    DB_HOST: 'localhost',
    DB_PORT: '5432',
    DB_NAME: 'carbonflow_dev',
    DB_USER: 'postgres',
    DB_PASSWORD: 'configured-through-environment',
    JWT_SECRET: 'configured-jwt-secret',
    REFRESH_TOKEN_SECRET: 'configured-refresh-secret',
  }));
});
