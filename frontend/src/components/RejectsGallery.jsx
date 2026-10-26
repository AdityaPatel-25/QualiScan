import React from "react";
import { AuthImage } from "./AuthImage.jsx";

/**
 * Gallery displaying recent rejected items with their snapshot evidence,
 * product category, specific failed check triggers, and capture timestamp.
 *
 * @param {Object} props
 * @param {Array<Object>} props.rejects - Filtered list of rejected inspections
 */
export function RejectsGallery({ rejects = [] }) {
  if (rejects.length === 0) {
    return (
      <div className="gallery-empty-card" data-testid="rejects-gallery-empty">
        <div className="empty-icon-shield">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            <path d="m9 12 2 2 4-4" />
          </svg>
        </div>
        <h4>All Inspections Nominal</h4>
        <p>No defective items have been flagged on this line in the recent telemetry buffer.</p>
      </div>
    );
  }

  return (
    <div className="rejects-gallery-grid" data-testid="rejects-gallery-grid">
      {rejects.map((item) => {
        const timeStr = item.inspectedAt
          ? new Date(item.inspectedAt).toLocaleTimeString()
          : "Just now";

        const failedList = Array.isArray(item.failedChecks)
          ? item.failedChecks
          : typeof item.failedChecks === "string" && item.failedChecks
          ? item.failedChecks.split(",").map((s) => s.trim()).filter(Boolean)
          : [];

        return (
          <div key={item.id || item.trackId} className="reject-card">
            {/* Snapshot Image with secure bearer token */}
            <div className="reject-image-container">
              <AuthImage
                snapshotId={item.id}
                url={item.snapshotUrl}
                alt={`Defect ${item.productType} track ${item.trackId}`}
                className="reject-snapshot"
              />
              <span className="reject-badge">REJECT</span>
            </div>

            {/* Reject Details */}
            <div className="reject-details">
              <div className="reject-meta-row">
                <span className="reject-product-tag">
                  {item.productType ? item.productType.toUpperCase() : "ITEM"}
                </span>
                <span className="reject-track-id">Track #{item.trackId}</span>
              </div>

              <div className="reject-timestamp">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10" />
                  <polyline points="12 6 12 12 16 14" />
                </svg>
                <span>{timeStr}</span>
                {item.confidence > 0 && (
                  <span className="reject-confidence">
                    {(item.confidence * 100).toFixed(0)}% conf
                  </span>
                )}
              </div>

              {/* Failed Checks Trigger Pills */}
              <div className="failed-checks-row">
                {failedList.length === 0 ? (
                  <span className="failed-check-pill">Defect</span>
                ) : (
                  failedList.map((check, idx) => (
                    <span key={idx} className="failed-check-pill">
                      {check}
                    </span>
                  ))
                )}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
