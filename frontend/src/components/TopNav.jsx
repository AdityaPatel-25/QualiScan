import React from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";

/**
 * Top navigation bar with branding, navigation links, line selector,
 * user details, and logout action.
 *
 * @param {Object} props
 * @param {Array<Object>} props.lines - Available production lines
 * @param {number|string} props.selectedLineId - Currently selected line ID
 * @param {function} props.onSelectLine - Handler when user switches line
 */
export function TopNav({ lines = [], selectedLineId, onSelectLine }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate("/login");
  };

  return (
    <header className="top-nav">
      <div className="nav-container">
        {/* Brand & Logo */}
        <div className="brand-section">
          <div className="brand-logo">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
              <path d="M3 7V5a2 2 0 0 1 2-2h2" />
              <path d="M17 3h2a2 2 0 0 1 2 2v2" />
              <path d="M21 17v2a2 2 0 0 1-2 2h-2" />
              <path d="M7 21H5a2 2 0 0 1-2-2v-2" />
              <circle cx="12" cy="12" r="3" />
              <path d="m10 15 4-6" />
            </svg>
          </div>
          <div className="brand-title">
            <span className="brand-name">QualiScan</span>
            <span className="brand-subtitle">VISION QA</span>
          </div>
        </div>

        {/* Navigation Links */}
        <nav className="nav-links">
          <NavLink
            to="/live"
            className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
          >
            Live
          </NavLink>
          <NavLink
            to="/history"
            className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
          >
            History
          </NavLink>
          <NavLink
            to="/alerts"
            className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
          >
            Alerts
          </NavLink>
          <NavLink
            to="/reports"
            className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
          >
            Reports
          </NavLink>
          <NavLink
            to="/settings"
            className={({ isActive }) => `nav-item ${isActive ? "active" : ""}`}
          >
            Settings
          </NavLink>
        </nav>

        {/* Right Section: Line Selector & User / Logout */}
        <div className="nav-controls">
          {/* Production Line Selector */}
          <div className="line-selector-wrapper">
            <label htmlFor="line-select" className="line-select-label">
              LINE:
            </label>
            <select
              id="line-select"
              className="line-select"
              value={selectedLineId || ""}
              onChange={(e) => onSelectLine && onSelectLine(Number(e.target.value))}
            >
              {lines.length === 0 ? (
                <option value="">No lines available</option>
              ) : (
                lines.map((line) => (
                  <option key={line.id} value={line.id}>
                    {line.name} {line.activeBatchId ? `(Batch #${line.activeBatchId})` : ""}
                  </option>
                ))
              )}
            </select>
          </div>

          {/* User Profile & Role Tag */}
          <div className="user-profile">
            <div className="user-avatar">
              {(user?.username || "U").slice(0, 1).toUpperCase()}
            </div>
            <div className="user-info">
              <span className="user-name">{user?.username || "Operator"}</span>
              <span className={`role-badge role-${(user?.role || "OPERATOR").toLowerCase()}`}>
                {user?.role || "OPERATOR"}
              </span>
            </div>
          </div>

          {/* Logout Action */}
          <button
            onClick={handleLogout}
            className="btn-logout"
            title="Log out of QualiScan"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            <span>Exit</span>
          </button>
        </div>
      </div>
    </header>
  );
}
