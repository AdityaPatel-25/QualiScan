import React from "react";

/**
 * High-visibility banner displayed when an active OPEN or ACKNOWLEDGED quality alert
 * is triggered on the conveyor line.
 *
 * @param {Object} props
 * @param {Array<Object>} props.alerts - List of active alerts
 * @param {number|string} [props.lineId] - Optional current line filter
 */
export function AlertsBanner({ alerts = [], lineId }) {
  // Filter for active alerts belonging to the current line (or global)
  const activeAlerts = alerts.filter((a) => {
    if (a.status !== "OPEN" && a.status !== "ACKNOWLEDGED") return false;
    if (lineId && a.lineId && Number(a.lineId) !== Number(lineId)) return false;
    return true;
  });

  if (activeAlerts.length === 0) {
    return null;
  }

  return (
    <div className="alerts-banner-container" data-testid="alerts-banner">
      {activeAlerts.map((alert) => {
        const isAcknowledged = alert.status === "ACKNOWLEDGED";
        const defectPercent = (alert.defectRate * 100).toFixed(1);
        const thresholdPercent = (alert.threshold * 100).toFixed(1);

        return (
          <div
            key={alert.id}
            className={`alert-banner-item ${isAcknowledged ? "banner-acknowledged" : "banner-open"}`}
          >
            <div className="alert-icon-wrapper">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
                <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
                <line x1="12" y1="9" x2="12" y2="13" />
                <line x1="12" y1="17" x2="12.01" y2="17" />
              </svg>
            </div>

            <div className="alert-content">
              <div className="alert-headline">
                <span className="alert-tag">
                  {alert.status} ALERT #{alert.id}
                </span>
                <span className="alert-product">
                  Target: {alert.productType ? alert.productType.toUpperCase() : "ALL PRODUCTS"}
                </span>
                <span className="alert-time">
                  {alert.createdAt ? new Date(alert.createdAt).toLocaleTimeString() : ""}
                </span>
              </div>
              <p className="alert-message">{alert.message}</p>
            </div>

            <div className="alert-stats-pill">
              <span className="rate-val">{defectPercent}%</span>
              <span className="rate-threshold">/ {thresholdPercent}% limit</span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
