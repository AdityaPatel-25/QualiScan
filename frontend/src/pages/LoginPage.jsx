import React, { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";

export function LoginPage({
  isDemoMode = String(import.meta.env.VITE_DEMO_MODE).toLowerCase() === "true",
  demoPassword = import.meta.env.VITE_DEMO_OPERATOR_PASSWORD || "ValidLongDemoPassword123!"
} = {}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const from = location.state?.from?.pathname || "/live";

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!username.trim() || !password) {
      setError("Please enter both username and password");
      return;
    }

    setError(null);
    setSubmitting(true);

    try {
      await login(username.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err.message || "Authentication failed. Please verify credentials.");
    } finally {
      setSubmitting(false);
    }
  };

  // Quick fill helper for demonstration and test convenience
  const fillCredentials = (u, p) => {
    setUsername(u);
    setPassword(p);
    setError(null);
  };

  return (
    <div className="login-page-container">
      <div className="login-card">
        {/* Header with QualiScan logo */}
        <div className="login-header">
          <div className="login-logo-badge">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
              <path d="M3 7V5a2 2 0 0 1 2-2h2" />
              <path d="M17 3h2a2 2 0 0 1 2 2v2" />
              <path d="M21 17v2a2 2 0 0 1-2 2h-2" />
              <path d="M7 21H5a2 2 0 0 1-2-2v-2" />
              <circle cx="12" cy="12" r="3" />
              <path d="m10 15 4-6" />
            </svg>
          </div>
          <h1 className="login-title">QualiScan</h1>
          <p className="login-subtitle">Conveyor Inspection & Defect Control Center</p>
        </div>

        {/* Error notification */}
        {error && (
          <div className="login-error-alert" data-testid="login-error">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>{error}</span>
          </div>
        )}

        {/* Login Form */}
        <form onSubmit={handleSubmit} className="login-form">
          <div className="form-group">
            <label htmlFor="username-input" className="form-label">
              Operator / Username
            </label>
            <input
              id="username-input"
              type="text"
              className="form-input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="e.g. operator"
              autoComplete="username"
              disabled={submitting}
              required
            />
          </div>

          <div className="form-group">
            <label htmlFor="password-input" className="form-label">
              Password
            </label>
            <input
              id="password-input"
              type="password"
              className="form-input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••••••"
              autoComplete="current-password"
              disabled={submitting}
              required
            />
          </div>

          <button
            type="submit"
            className="btn-primary btn-block"
            disabled={submitting}
            data-testid="login-submit-button"
          >
            {submitting ? (
              <span className="btn-loading-wrapper">
                <span className="spinner-mini"></span>
                <span>Authenticating...</span>
              </span>
            ) : (
              <span>Sign In to Station</span>
            )}
          </button>
        </form>

        {/* Read-Only Public Demo Account Card */}
        {isDemoMode && (
          <div className="demo-account-card" data-testid="demo-account-card">
            <div className="demo-account-header">
              <span className="demo-badge-readonly">Public Demo Account (Read-Only)</span>
              <p className="demo-account-desc">
                This instance is a public demonstration station with simulated live data. Mutating actions are restricted.
              </p>
            </div>
            <div className="demo-account-creds">
              <div className="demo-cred-row">
                <span className="demo-cred-label">User:</span>
                <code className="demo-cred-value" data-testid="demo-operator-user">operator</code>
              </div>
              <div className="demo-cred-row">
                <span className="demo-cred-label">Password:</span>
                <code className="demo-cred-value" data-testid="demo-operator-pass">{demoPassword}</code>
              </div>
            </div>
            <button
              type="button"
              className="btn-demo-fill"
              data-testid="demo-fill-button"
              onClick={() => fillCredentials("operator", demoPassword)}
            >
              Fill in
            </button>
          </div>
        )}

        {/* Local Dev Accounts Footer (Hidden in Demo Mode) */}
        {!isDemoMode && (
          <div className="demo-credentials-section">
            <span className="demo-label">Demo Station Accounts:</span>
            <div className="demo-pills-row">
              <button
                type="button"
                className="demo-pill"
                onClick={() => fillCredentials("admin", "Admin@123")}
              >
                admin
              </button>
              <button
                type="button"
                className="demo-pill"
                onClick={() => fillCredentials("supervisor", "Super@123")}
              >
                supervisor
              </button>
              <button
                type="button"
                className="demo-pill"
                onClick={() => fillCredentials("operator", "Oper@123")}
              >
                operator
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
