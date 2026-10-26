/**
 * Role hierarchy for QualiScan RBAC authorization:
 * ADMIN > SUPERVISOR > OPERATOR
 */
export const ROLE_HIERARCHY = {
  ADMIN: 3,
  SUPERVISOR: 2,
  OPERATOR: 1,
};

/**
 * Normalizes a role string (stripping any "ROLE_" prefix if present and uppercasing).
 * @param {string|null|undefined} role
 * @returns {string}
 */
export function normalizeRole(role) {
  if (!role || typeof role !== "string") return "";
  const cleaned = role.toUpperCase().trim();
  return cleaned.startsWith("ROLE_") ? cleaned.slice(5) : cleaned;
}

/**
 * Checks whether the candidate role has at least the minimum role privileges.
 * Hierarchy: ADMIN (3) > SUPERVISOR (2) > OPERATOR (1).
 *
 * @param {string} candidateRole - Role of the logged-in user
 * @param {string} minRole - Required minimum role
 * @returns {boolean} True if candidateRole meets or exceeds minRole
 */
export function hasRole(candidateRole, minRole) {
  const userRank = ROLE_HIERARCHY[normalizeRole(candidateRole)] || 0;
  const targetRank = ROLE_HIERARCHY[normalizeRole(minRole)] || 0;
  return userRank >= targetRank;
}
