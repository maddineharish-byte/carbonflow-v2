export interface ProductionConfigurationResult {
  production: boolean;
  missing: string[];
}

export function inspectProductionConfiguration(env: NodeJS.ProcessEnv = process.env): ProductionConfigurationResult {
  const production = env.NODE_ENV === 'production';
  if (!production) return { production, missing: [] };

  const required = [
    ['DB_HOST', env.DB_HOST],
    ['DB_PORT', env.DB_PORT],
    ['DB_NAME', env.DB_NAME],
    ['DB_USER', env.DB_USER],
    ['DB_PASSWORD', env.DB_PASSWORD],
    ['JWT_SECRET', env.JWT_SECRET],
    ['REFRESH_TOKEN_SECRET', env.REFRESH_TOKEN_SECRET],
  ] as const;

  const missing = required
    .filter(([, value]) => typeof value !== 'string' || value.trim() === '')
    .map(([name]) => name);
  return { production, missing };
}

export function assertProductionConfiguration(env: NodeJS.ProcessEnv = process.env): void {
  const result = inspectProductionConfiguration(env);
  if (result.missing.length > 0) {
    throw new Error(`Production configuration is incomplete: ${result.missing.join(', ')}`);
  }
}
