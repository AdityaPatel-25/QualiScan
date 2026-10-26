import React from "react";
import { Link } from "react-router-dom";

/**
 * Placeholder page for future phases (History, Alerts, Reports, Settings).
 *
 * @param {Object} props
 * @param {string} props.title
 * @param {string} props.description
 */
export function PlaceholderPage({
  title = "Module In Development",
  description = "This module is slated for subsequent iteration phases.",
}) {
  return (
    <div className="placeholder-page-container">
      <div className="placeholder-card">
        <div className="placeholder-icon">
          <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
          </svg>
        </div>
        <h2 className="placeholder-title">{title}</h2>
        <p className="placeholder-desc">{description}</p>
        <span className="placeholder-badge">Coming Next — Day 6 Part B</span>
        <div className="placeholder-action">
          <Link to="/live" className="btn-secondary">
            Return to Live Dashboard
          </Link>
        </div>
      </div>
    </div>
  );
}
