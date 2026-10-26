import React from "react";

/**
 * KPI Metric card with optional threshold exceedance highlight.
 *
 * @param {Object} props
 * @param {string} props.title - Card header label
 * @param {string|number} props.value - Primary metric display
 * @param {string} [props.subtitle] - Supporting information (e.g. yield percentage)
 * @param {'pass'|'reject'|'total'|'rate'|'default'} [props.type='default'] - Card theme
 * @param {boolean} [props.isExceeded] - Whether defect threshold is breached
 * @param {string} [props.thresholdInfo] - Threshold limit descriptor
 */
export function KpiCard({
  title,
  value,
  subtitle,
  type = "default",
  isExceeded = false,
  thresholdInfo,
}) {
  const cardClass = [
    "kpi-card",
    `kpi-${type}`,
    isExceeded ? "threshold-breached" : "",
  ].filter(Boolean).join(" ");

  return (
    <div className={cardClass} data-testid={`kpi-card-${type}`}>
      <div className="kpi-header">
        <span className="kpi-title">{title}</span>
        {thresholdInfo && (
          <span className={`kpi-threshold ${isExceeded ? "text-danger" : ""}`}>
            {thresholdInfo}
          </span>
        )}
      </div>

      <div className="kpi-body">
        <span className="kpi-value">{value}</span>
      </div>

      {subtitle && <div className="kpi-footer">{subtitle}</div>}
    </div>
  );
}
