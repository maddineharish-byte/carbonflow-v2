/**
 * CarbonFlow — Permission helpers (Phase 8, workstream 8.4).
 *
 * The backend session exposes the caller's permission codes
 * (`AuthSession.permissions`, the frozen 44-code RBAC matrix). Navigation
 * consumes those codes directly — no second permission matrix is defined
 * here, and frontend hiding is UX only: the backend remains the security
 * boundary.
 */

/** True when the session's permission codes include the given code. */
export function hasPermission(permissions: string[] | undefined | null, code: string): boolean {
  return Array.isArray(permissions) && permissions.includes(code);
}
