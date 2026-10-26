import React from "react";

/**
 * Universal industrial status badge for verdicts, alert states, and batch conditions.
 *
 * @param {Object} props
 * @param {string} props.status - e.g. PASS, REJECT, OPEN, ACKNOWLEDGED, RESOLVED, CLOSED
 * @param {string} [props.label] - Optional override display label
 * @param {'sm'|'md'} [props.size='md']
 */
export function StatusBadge({ status = "UNKNOWN", label, size = "md" }) {
  const norm = String(status || "").toUpperCase();

  const getStyleClass = (val) => {
    switch (val) {
      case "PASS":
      case "RESOLVED":
      case "CLOSED":
        return "badge-success";
      case "REJECT":
      case "OPEN":
      case "BREACH":
        return "badge-danger";
      case "ACKNOWLEDGED":
      case "WARNING":
        return "badge-warning";
      case "ACTIVE":
      case "INFO":
        return "badge-info";
      default:
        return "badge-neutral";
    }
  };

  const displayText = label || norm;

  return (
    <span
      className={`status-badge ${getStyleClass(norm)} badge-size-${size}`}
      data-testid="status-badge"
    >
      <span className="badge-dot"></span>
      <span className="badge-label">{displayText}</span>
    </span>
  );
}
