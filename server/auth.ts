/**
 * CarbonFlow — Authentication, JWT Lifecycle & Multi-Tenant Context Middleware
 */
import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import crypto from 'crypto';
import { db } from './db.ts';
import {
  createRefreshToken,
  revokeRefreshTokenFamilyByTokenHash,
  rotateRefreshToken as rotatePostgresRefreshToken,
  type PersistedRefreshContext,
  RefreshPersistenceError,
} from './postgres-refresh-repository.ts';
import type { Organization, User } from './types.ts';
import { TenantContext, RoleName, PermissionCode } from './types.ts';
import { hasPermission, ROLE_PERMISSIONS } from './rbac.ts';
import { findPersistedPrincipalById, type PersistedPrincipal } from './identity-repository.ts';

const JWT_SECRET = process.env.JWT_SECRET || 'carbonflow-jwt-super-secret-key-2026-sha256';
const REFRESH_SECRET = process.env.REFRESH_TOKEN_SECRET || 'carbonflow-refresh-super-secret-key-2026-sha512';
const REFRESH_TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface AuthenticatedRequest extends Request {
  tenantContext?: TenantContext;
}

export interface JwtPayload {
  userId: string;
  organizationId: string;
  role: RoleName;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

/**
 * Hashes an opaque refresh token before it enters PostgreSQL.
 * HMAC keeps the stored value tied to the configured server secret while the
 * raw token remains available only to the client that receives the response.
 */
export function hashRefreshToken(rawRefreshToken: string): string {
  return crypto.createHmac('sha256', REFRESH_SECRET).update(rawRefreshToken).digest('hex');
}

export function isValidRefreshTokenFormat(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value);
}

/**
 * Issues the access JWT only after the PostgreSQL refresh record commits.
 * The JWT retains the existing semantic IDs used by the in-memory domain
 * routes; PostgreSQL receives only canonical UUIDs.
 */
export async function generatePostgresTokenPair(user: User, organization: Organization, role: RoleName): Promise<TokenPair> {
  const familyId = crypto.randomUUID();
  const rawRefreshToken = crypto.randomBytes(32).toString('hex');
  const expiresAt = new Date(Date.now() + REFRESH_TOKEN_TTL_MS);
  await createRefreshToken(user, organization, role, hashRefreshToken(rawRefreshToken), familyId, expiresAt);
  const accessToken = jwt.sign(
    { userId: user.id, organizationId: organization.id, role } satisfies JwtPayload,
    JWT_SECRET,
    { expiresIn: '15m' },
  );
  return { accessToken, refreshToken: rawRefreshToken };
}

function semanticContextForPersistedToken(context: PersistedRefreshContext) {
  const user = db.users.find((candidate) =>
    candidate.email.trim().toLowerCase() === context.userEmail.trim().toLowerCase() && candidate.isActive,
  );
  if (!user) throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');

  const organization = db.organizations.find((candidate) =>
    context.organizationTaxId
      ? candidate.taxId === context.organizationTaxId
      : candidate.name === context.organizationName && candidate.country === context.organizationCountry,
  );
  if (!organization) throw new RefreshPersistenceError('REFRESH_MEMBERSHIP_INVALID', 'The associated organization is unavailable.');

  const membership = db.memberships.find((candidate) =>
    candidate.userId === user.id &&
    candidate.organizationId === organization.id &&
    candidate.role === context.roleName &&
    candidate.isActive,
  );
  if (!membership) throw new RefreshPersistenceError('REFRESH_MEMBERSHIP_INVALID', 'The associated organization membership is no longer active.');

  return { user, organization, role: context.roleName };
}

function semanticContextForPersistedIdentity(principal: PersistedPrincipal, context: PersistedRefreshContext) {
  if (!principal.user.isActive) {
    throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');
  }
  const membership = principal.memberships.find((candidate) =>
    candidate.organization.id === context.organizationId && candidate.role === context.roleName && candidate.isActive,
  );
  if (!membership) {
    throw new RefreshPersistenceError('REFRESH_MEMBERSHIP_INVALID', 'The associated organization membership is no longer active.');
  }
  return { user: principal.user, organization: membership.organization, role: membership.role };
}

async function validatePersistedIdentity(context: PersistedRefreshContext) {
  const principal = await findPersistedPrincipalById(context.userId);
  if (!principal) throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');
  semanticContextForPersistedIdentity(principal, context);
}

/** Rotate a PostgreSQL-backed refresh token and issue a new access JWT. */
export async function rotatePostgresTokenPair(rawRefreshToken: string): Promise<TokenPair> {
  let rawReplacement = '';
  const semanticValidator = process.env.NODE_ENV === 'production'
    ? validatePersistedIdentity
    : semanticContextForPersistedToken;
  const rotated = await rotatePostgresRefreshToken(
    hashRefreshToken(rawRefreshToken),
    () => {
      rawReplacement = crypto.randomBytes(32).toString('hex');
      return {
        id: crypto.randomUUID(),
        tokenHash: hashRefreshToken(rawReplacement),
        expiresAt: new Date(Date.now() + REFRESH_TOKEN_TTL_MS),
      };
    },
    semanticValidator,
  );
  let semantic;
  if (process.env.NODE_ENV === 'production') {
    const principal = await findPersistedPrincipalById(rotated.context.userId);
    if (!principal) throw new RefreshPersistenceError('USER_DEACTIVATED', 'The associated user is inactive or deleted.');
    semantic = semanticContextForPersistedIdentity(principal, rotated.context);
  } else {
    semantic = semanticContextForPersistedToken(rotated.context);
  }
  const accessToken = jwt.sign(
    { userId: semantic.user.id, organizationId: semantic.organization.id, role: semantic.role } satisfies JwtPayload,
    JWT_SECRET,
    { expiresIn: '15m' },
  );
  return { accessToken, refreshToken: rawReplacement };
}

/** Revoke the PostgreSQL family represented by an opaque refresh token. */
export async function revokePostgresRefreshTokenFamily(rawRefreshToken: string): Promise<void> {
  await revokeRefreshTokenFamilyByTokenHash(hashRefreshToken(rawRefreshToken));
}

/**
 * Middleware: Derives and cryptographically verifies TenantContext.
 * Reject any untrusted organization claims.
 *//**
 * Middleware: Derives and cryptographically verifies TenantContext.
 * Reject any untrusted organization claims.
 */
export async function authenticateTenant(req: AuthenticatedRequest, res: Response, next: NextFunction) {
  let token: string | undefined;
  const authHeader = req.headers.authorization;
  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.split(' ')[1];
  } else if (typeof req.query.token === 'string') {
    token = req.query.token;
  }

  if (!token) {
    return res.status(401).json({
      success: false,
      error: { code: 'UNAUTHORIZED', message: 'Missing or malformed Authorization header or token query parameter.' },
    });
  }
  try {
    const decoded = jwt.verify(token, JWT_SECRET) as JwtPayload;
    if (process.env.NODE_ENV === 'production') {
      try {
        const principal = await findPersistedPrincipalById(decoded.userId);
        if (!principal?.user.isActive) {
          return res.status(401).json({ success: false, error: { code: 'USER_DEACTIVATED', message: 'User account is inactive or deleted.' } });
        }
        const membership = principal.memberships.find((candidate) => candidate.organization.id === decoded.organizationId && candidate.isActive);
        if (!membership) {
          return res.status(403).json({ success: false, error: { code: 'TENANT_ACCESS_DENIED', message: 'User does not belong to this organization.' } });
        }
        req.tenantContext = {
          organizationId: membership.organization.id,
          userId: principal.user.id,
          role: membership.role,
          permissions: ROLE_PERMISSIONS[membership.role] || [],
        };
        return next();
      } catch {
        return res.status(503).json({ success: false, error: { code: 'AUTH_PERSISTENCE_UNAVAILABLE', message: 'Authentication persistence is temporarily unavailable.' } });
      }
    }
    const user = db.users.find((u) => u.id === decoded.userId && u.isActive);
    if (!user) {
      return res.status(401).json({
        success: false,
        error: { code: 'USER_DEACTIVATED', message: 'User account is inactive or deleted.' },
      });
    }

    // Crucial: Verify user actually belongs to requested organization
    const membership = db.memberships.find(
      (m) => m.userId === decoded.userId && m.organizationId === decoded.organizationId && m.isActive
    );

    if (!membership) {
      return res.status(403).json({
        success: false,
        error: { code: 'TENANT_ACCESS_DENIED', message: 'User does not belong to this organization.' },
      });
    }

    req.tenantContext = {
      organizationId: membership.organizationId,
      userId: user.id,
      role: membership.role,
      permissions: ROLE_PERMISSIONS[membership.role] || [],
    };

    next();
  } catch (err) {
    return res.status(401).json({
      success: false,
      error: { code: 'INVALID_TOKEN', message: 'Access token expired or signature invalid.' },
    });
  }
}

/**
 * Middleware: Enforces canonical RBAC permission server-side.
 */
export function requirePermission(permission: PermissionCode) {
  return (req: AuthenticatedRequest, res: Response, next: NextFunction) => {
    if (!req.tenantContext) {
      return res.status(401).json({
        success: false,
        error: { code: 'UNAUTHORIZED', message: 'Authentication required.' },
      });
    }

    if (!hasPermission(req.tenantContext.role, permission)) {
      return res.status(403).json({
        success: false,
        error: {
          code: 'FORBIDDEN',
          message: `Role '${req.tenantContext.role}' lacks required permission '${permission}'.`,
        },
      });
    }

    next();
  };
}
