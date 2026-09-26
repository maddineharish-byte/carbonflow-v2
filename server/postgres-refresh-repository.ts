/**
 * PostgreSQL-backed refresh-token persistence.
 *
 * The PostgreSQL row is the only production source of truth for refresh
 * operations. This module does not fall back to server/db.ts or cache tokens.
 */
// @ts-expect-error pg is an existing runtime dependency without bundled TypeScript declarations.
import pg from 'pg';
import crypto from 'crypto';
import type { Organization, RoleName, User } from './types.ts';

const { Pool } = pg;
let pool: any;

function getPool(): any {
  if (pool) return pool;
  if (!process.env.DB_PASSWORD) {
    throw new Error('AUTH_PERSISTENCE_UNAVAILABLE: database password is not configured');
  }
  pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: Number(process.env.DB_PORT || 5432),
    database: process.env.DB_NAME || 'carbonflow_dev',
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD,
    connectionTimeoutMillis: 5000,
    idleTimeoutMillis: 10_000,
  });
  return pool;
}

export interface PersistedRefreshContext {
  id: string;
  userId: string;
  organizationId: string;
  roleId: string;
  familyId: string;
  tokenHash: string;
  expiresAt: string;
  revokedAt?: string;
  replacedByTokenId?: string;
  createdAt: string;
  userEmail: string;
  userActive: boolean;
  organizationTaxId?: string;
  organizationName: string;
  organizationCountry: string;
  roleName: RoleName;
  membershipActive: boolean;
}

export interface ReplacementMaterial {
  id: string;
  tokenHash: string;
  expiresAt: Date;
}

export class RefreshPersistenceError extends Error {
  constructor(
    public readonly code: 'INVALID_REFRESH_TOKEN' | 'REFRESH_TOKEN_REVOKED' | 'REFRESH_TOKEN_EXPIRED' | 'USER_DEACTIVATED' | 'REFRESH_MEMBERSHIP_INVALID' | 'AUTH_PERSISTENCE_UNAVAILABLE',
    message: string,
    public readonly cause?: unknown,
  ) {
    super(message);
    this.name = 'RefreshPersistenceError';
  }
}

function persistenceError(cause: unknown): RefreshPersistenceError {
  return new RefreshPersistenceError(
    'AUTH_PERSISTENCE_UNAVAILABLE',
    'Authentication persistence is temporarily unavailable.',
    cause,
  );
}

async function resolveCanonicalIdentity(
  client: any,
  user: User,
  organization: Organization,
  role: RoleName,
): Promise<{ userId: string; organizationId: string; roleId: string; roleName: RoleName }> {
  const normalizedEmail = user.email.trim().toLowerCase();
  if (!normalizedEmail) throw new RefreshPersistenceError('AUTH_PERSISTENCE_UNAVAILABLE', 'User email is required for persistence.');

  const existingUser = await client.query(
    'SELECT id::text, is_active FROM users WHERE lower(email) = $1 LIMIT 1',
    [normalizedEmail],
  );
  let userId: string;
  if (existingUser.rows[0]) {
    if (!existingUser.rows[0].is_active) {
      throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');
    }
    userId = existingUser.rows[0].id;
  } else {
    const insertedUser = await client.query(
      `INSERT INTO users (email, password_hash, full_name, is_active)
       VALUES ($1, $2, $3, $4)
       RETURNING id::text`,
      [normalizedEmail, user.passwordHash, user.fullName, user.isActive],
    );
    userId = insertedUser.rows[0].id;
  }

  // Organization tax_id is the stable development/business key when present.
  // For organizations without tax_id, use the normalized name and country.
  const organizationKey = organization.taxId
    ? `tax:${organization.taxId.trim()}`
    : `name:${organization.name.trim().toLowerCase()}:${organization.country.trim().toLowerCase()}`;
  await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [organizationKey]);
  const existingOrganization = await client.query(
    organization.taxId
      ? 'SELECT id::text FROM organizations WHERE tax_id = $1 ORDER BY id LIMIT 1'
      : 'SELECT id::text FROM organizations WHERE lower(name) = lower($1) AND country = $2 ORDER BY id LIMIT 1',
    organization.taxId ? [organization.taxId.trim()] : [organization.name.trim(), organization.country.trim()],
  );
  let organizationId: string;
  if (existingOrganization.rows[0]) {
    organizationId = existingOrganization.rows[0].id;
  } else {
    const insertedOrganization = await client.query(
      `INSERT INTO organizations (name, tax_id, country, industry)
       VALUES ($1, $2, $3, $4)
       RETURNING id::text`,
      [organization.name.trim(), organization.taxId || null, organization.country, organization.industry || null],
    );
    organizationId = insertedOrganization.rows[0].id;
  }

  const roleRow = await client.query(
    'SELECT id::text, name FROM roles WHERE lower(name) = lower($1)',
    [role],
  );
  if (!roleRow.rows[0]) throw new RefreshPersistenceError('AUTH_PERSISTENCE_UNAVAILABLE', 'The canonical role is not provisioned.');
  const roleId = roleRow.rows[0].id;
  const roleName = roleRow.rows[0].name;

  const membership = await client.query(
    `SELECT om.id::text, om.role_id::text, om.is_active, r.name AS role_name
     FROM organization_memberships om
     JOIN roles r ON r.id = om.role_id
     WHERE om.organization_id = $1 AND om.user_id = $2`,
    [organizationId, userId],
  );
  if (membership.rows[0]) {
    if (!membership.rows[0].is_active || membership.rows[0].role_name !== roleName) {
      throw new RefreshPersistenceError('REFRESH_MEMBERSHIP_INVALID', 'The organization membership is not active for the requested role.');
    }
  } else {
    await client.query(
      `INSERT INTO organization_memberships (organization_id, user_id, role_id, is_active)
       VALUES ($1, $2, $3, $4)`,
      [organizationId, userId, roleId, true],
    );
  }

  return { userId, organizationId, roleId, roleName };
}

export async function createRefreshToken(
  user: User,
  organization: Organization,
  role: RoleName,
  tokenHash: string,
  familyId: string,
  expiresAt: Date,
): Promise<PersistedRefreshContext> {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const identity = await resolveCanonicalIdentity(client, user, organization, role);
    const inserted = await client.query(
      `INSERT INTO refresh_tokens
         (user_id, organization_id, role_id, family_id, token_hash, expires_at, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, CURRENT_TIMESTAMP)
       RETURNING id::text, user_id::text, organization_id::text, role_id::text,
                 family_id::text, token_hash, expires_at::text, revoked_at::text,
                 replaced_by_token_id::text, created_at::text`,
      [identity.userId, identity.organizationId, identity.roleId, familyId, tokenHash, expiresAt],
    );
    await client.query('COMMIT');
    return mapContext(inserted.rows[0], user.email.trim().toLowerCase(), true, organization.taxId, organization.name, organization.country, identity.roleName, true);
  } catch (error) {
    await client.query('ROLLBACK');
    if (error instanceof RefreshPersistenceError) throw error;
    throw persistenceError(error);
  } finally {
    client.release();
  }
}

function mapContext(
  row: Record<string, unknown>,
  userEmail: string,
  userActive: boolean,
  organizationTaxId: string | undefined,
  organizationName: string,
  organizationCountry: string,
  roleName: RoleName,
  membershipActive: boolean,
): PersistedRefreshContext {
  return {
    id: String(row.id), userId: String(row.user_id), organizationId: String(row.organization_id),
    roleId: String(row.role_id), familyId: String(row.family_id), tokenHash: String(row.token_hash),
    expiresAt: String(row.expires_at), revokedAt: row.revoked_at ? String(row.revoked_at) : undefined,
    replacedByTokenId: row.replaced_by_token_id ? String(row.replaced_by_token_id) : undefined,
    createdAt: String(row.created_at), userEmail, userActive, organizationTaxId,
    organizationName, organizationCountry, roleName, membershipActive,
  };
}

async function selectLockedToken(client: any, tokenHash: string) {
  return client.query(
    `SELECT rt.id::text, rt.user_id::text, rt.organization_id::text, rt.role_id::text,
            rt.family_id::text, rt.token_hash, rt.expires_at::text, rt.revoked_at::text,
            rt.replaced_by_token_id::text, rt.created_at::text,
            u.email, u.is_active AS user_active,
            o.tax_id, o.name AS organization_name, o.country AS organization_country,
            r.name AS role_name,
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
     FOR UPDATE OF rt`,
    [tokenHash],
  );
}

export async function rotateRefreshToken(
  tokenHash: string,
  replacementFactory: (context: PersistedRefreshContext) => ReplacementMaterial,
  semanticValidator?: (context: PersistedRefreshContext) => void,
): Promise<{ context: PersistedRefreshContext; replacement: ReplacementMaterial }> {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const selected = await selectLockedToken(client, tokenHash);
    if (!selected.rows[0]) {
      await client.query('ROLLBACK');
      throw new RefreshPersistenceError('INVALID_REFRESH_TOKEN', 'The refresh token is invalid.');
    }
    const row = selected.rows[0];
    const context = mapContext(
      row, String(row.email), Boolean(row.user_active), row.tax_id ? String(row.tax_id) : undefined,
      String(row.organization_name), String(row.organization_country), row.role_name as RoleName, Boolean(row.membership_active),
    );
    if (context.revokedAt || context.replacedByTokenId) {
      await client.query('ROLLBACK');
      throw new RefreshPersistenceError('REFRESH_TOKEN_REVOKED', 'The refresh token has already been revoked.');
    }
    if (new Date(context.expiresAt).getTime() <= Date.now()) {
      await client.query('UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE id = $1', [context.id]);
      await client.query('COMMIT');
      throw new RefreshPersistenceError('REFRESH_TOKEN_EXPIRED', 'The refresh token has expired.');
    }
    if (!context.userActive) {
      await client.query('UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE id = $1', [context.id]);
      await client.query('COMMIT');
      throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');
    }
    if (!context.membershipActive) {
      await client.query('UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE id = $1', [context.id]);
      await client.query('COMMIT');
      throw new RefreshPersistenceError('REFRESH_MEMBERSHIP_INVALID', 'The associated organization membership is no longer active.');
    }
    if (semanticValidator) {
      try {
        semanticValidator(context);
      } catch (error) {
        if (error instanceof RefreshPersistenceError) {
          await client.query('UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE id = $1', [context.id]);
          await client.query('COMMIT');
        } else {
          await client.query('ROLLBACK');
        }
        throw error;
      }
    }
    const replacement = replacementFactory(context);
    await client.query(
      `INSERT INTO refresh_tokens
         (id, user_id, organization_id, role_id, family_id, token_hash, expires_at, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP)`,
      [replacement.id, context.userId, context.organizationId, context.roleId, context.familyId, replacement.tokenHash, replacement.expiresAt],
    );
    await client.query(
      `UPDATE refresh_tokens
       SET revoked_at = CURRENT_TIMESTAMP, replaced_by_token_id = $1
       WHERE id = $2`,
      [replacement.id, context.id],
    );
    await client.query('COMMIT');
    return { context, replacement };
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch { /* connection is being discarded */ }
    if (error instanceof RefreshPersistenceError) throw error;
    throw persistenceError(error);
  } finally {
    client.release();
  }
}

export async function revokeRefreshTokenFamilyByTokenHash(tokenHash: string): Promise<void> {
  const client = await getPool().connect();
  try {
    await client.query('BEGIN');
    const selected = await client.query(
      'SELECT id::text, family_id::text, revoked_at::text, replaced_by_token_id::text FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE',
      [tokenHash],
    );
    if (!selected.rows[0]) {
      await client.query('ROLLBACK');
      throw new RefreshPersistenceError('INVALID_REFRESH_TOKEN', 'The refresh token is invalid.');
    }
    if (selected.rows[0].revoked_at || selected.rows[0].replaced_by_token_id) {
      await client.query('ROLLBACK');
      throw new RefreshPersistenceError('REFRESH_TOKEN_REVOKED', 'The refresh token has already been revoked.');
    }
    await client.query(
      'UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE family_id = $1 AND revoked_at IS NULL',
      [selected.rows[0].family_id],
    );
    await client.query('COMMIT');
  } catch (error) {
    try { await client.query('ROLLBACK'); } catch { /* connection is being discarded */ }
    if (error instanceof RefreshPersistenceError) throw error;
    throw persistenceError(error);
  } finally {
    client.release();
  }
}

export async function closeRefreshPersistence(): Promise<void> {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}
