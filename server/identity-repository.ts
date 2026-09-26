// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import type { Organization, RoleName, User } from './types.ts';

const { Pool } = pg;
let pool: any;

function getPool(): any {
  if (pool) return pool;
  if (!process.env.DB_PASSWORD) throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
  pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    database: process.env.DB_NAME || 'carbonflow_dev',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD,
    connectionTimeoutMillis: 5000,
  });
  return pool;
}

export interface PersistedMembership {
  organization: Organization;
  role: RoleName;
  isActive: boolean;
}

export interface PersistedPrincipal {
  user: User;
  memberships: PersistedMembership[];
}

function mapOrganization(row: any): Organization {
  return {
    id: String(row.organization_id),
    name: String(row.organization_name),
    taxId: row.tax_id ? String(row.tax_id) : undefined,
    country: String(row.country),
    industry: String(row.industry || ''),
    consolidationApproach: row.consolidation_approach || 'OPERATIONAL_CONTROL',
    baseYear: Number(row.base_year || 2023),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

async function loadPrincipal(userId: string): Promise<PersistedPrincipal | undefined> {
  const userResult = await getPool().query(
    `SELECT id::text, email, password_hash, full_name, is_active, created_at::text
       FROM users WHERE id = $1`,
    [userId],
  );
  if (!userResult.rows[0]) return undefined;
  const user: User = {
    id: String(userResult.rows[0].id),
    email: String(userResult.rows[0].email),
    passwordHash: String(userResult.rows[0].password_hash),
    fullName: String(userResult.rows[0].full_name),
    isActive: Boolean(userResult.rows[0].is_active),
    createdAt: String(userResult.rows[0].created_at),
  };
  const membershipResult = await getPool().query(
    `SELECT om.is_active, r.name AS role_name,
            o.id AS organization_id, o.name AS organization_name, o.tax_id,
            o.country, o.industry, o.created_at::text, o.updated_at::text,
            os.consolidation_approach, os.base_year
       FROM organization_memberships om
       JOIN roles r ON r.id = om.role_id
       JOIN organizations o ON o.id = om.organization_id
       LEFT JOIN organization_settings os ON os.organization_id = o.id
      WHERE om.user_id = $1
      ORDER BY o.name`,
    [userId],
  );
  return {
    user,
    memberships: membershipResult.rows.map((row: any) => ({
      organization: mapOrganization(row),
      role: row.role_name as RoleName,
      isActive: Boolean(row.is_active),
    })),
  };
}

export async function findPersistedPrincipalByEmail(email: string): Promise<PersistedPrincipal | undefined> {
  if (!process.env.DB_PASSWORD) throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
  const result = await getPool().query('SELECT id::text FROM users WHERE lower(email) = lower($1) LIMIT 1', [email.trim()]);
  return result.rows[0] ? loadPrincipal(String(result.rows[0].id)) : undefined;
}

export async function findPersistedPrincipalById(userId: string): Promise<PersistedPrincipal | undefined> {
  if (!process.env.DB_PASSWORD) throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
  return loadPrincipal(userId);
}

export async function closeIdentityPersistence(): Promise<void> {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}
