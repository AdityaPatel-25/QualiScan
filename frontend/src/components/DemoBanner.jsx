import React from "react";

/**
 * Thin, always-visible banner indicating demo mode with simulated data.
 * Displays only when VITE_DEMO_MODE=true.
 */
export function DemoBanner({
  isDemo = String(import.meta.env.VITE_DEMO_MODE).toLowerCase() === "true",
  repoUrl = import.meta.env.VITE_REPO_URL || "https://github.com/AdityaPatel-25/QualiScan"
}) {
  if (!isDemo) return null;

  return (
    <aside
      className="demo-mode-banner"
      role="status"
      aria-label="Demo mode indicator"
      data-testid="demo-mode-banner"
    >
      <div className="demo-mode-banner-content">
        <span className="demo-mode-pill">DEMO</span>
        <span className="demo-mode-text">
          Demo mode: simulated live data. Real accuracy results are in the{" "}
          <a
            href={repoUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="demo-mode-link"
          >
            project README
          </a>
          .
        </span>
      </div>
    </aside>
  );
}
