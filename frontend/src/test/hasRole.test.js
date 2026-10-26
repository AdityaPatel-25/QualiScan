import { describe, it, expect } from "vitest";
import { hasRole, normalizeRole, ROLE_HIERARCHY } from "../auth/roles.js";

describe("RBAC hasRole hierarchy (ADMIN > SUPERVISOR > OPERATOR)", () => {
  it("normalizes roles properly regardless of casing and ROLE_ prefix", () => {
    expect(normalizeRole("ROLE_ADMIN")).toBe("ADMIN");
    expect(normalizeRole("supervisor")).toBe("SUPERVISOR");
    expect(normalizeRole("OPERATOR")).toBe("OPERATOR");
    expect(normalizeRole(null)).toBe("");
  });

  it("verifies ADMIN rank exceeds or equals all roles", () => {
    expect(hasRole("ADMIN", "ADMIN")).toBe(true);
    expect(hasRole("ADMIN", "SUPERVISOR")).toBe(true);
    expect(hasRole("ADMIN", "OPERATOR")).toBe(true);
    expect(hasRole("ROLE_ADMIN", "OPERATOR")).toBe(true);
  });

  it("verifies SUPERVISOR has supervisor and operator rights, but not admin", () => {
    expect(hasRole("SUPERVISOR", "SUPERVISOR")).toBe(true);
    expect(hasRole("SUPERVISOR", "OPERATOR")).toBe(true);
    expect(hasRole("SUPERVISOR", "ADMIN")).toBe(false);
  });

  it("verifies OPERATOR has only operator rights", () => {
    expect(hasRole("OPERATOR", "OPERATOR")).toBe(true);
    expect(hasRole("OPERATOR", "SUPERVISOR")).toBe(false);
    expect(hasRole("OPERATOR", "ADMIN")).toBe(false);
  });

  it("rejects unknown or null roles", () => {
    expect(hasRole(null, "OPERATOR")).toBe(false);
    expect(hasRole("GUEST", "OPERATOR")).toBe(false);
    expect(hasRole(undefined, "ADMIN")).toBe(false);
  });
});
