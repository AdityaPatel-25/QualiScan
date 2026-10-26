import React from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";
import { hasRole } from "./roles.js";

/**
 * Route protection wrapper requiring an active authenticated session.
 * Optionally enforces a minimum required role in the RBAC hierarchy.
 *
 * @param {Object} props
 * @param {React.ReactNode} [props.children]
 * @param {string} [props.minRole] - Optional minimum role required
 */
export function ProtectedRoute({ children, minRole }) {
  const { user, isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (minRole && !hasRole(user?.role, minRole)) {
    return (
      <div className="forbidden-container" style={{ padding: "40px", textAlign: "center" }}>
        <h2>403 Forbidden</h2>
        <p>Your role ({user?.role}) does not have permission to view this resource.</p>
      </div>
    );
  }

  return children;
}
