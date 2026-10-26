import React from "react";

/**
 * Visual status indicator badge for STOMP WebSocket connection.
 * Colors: Green (connected), Amber (reconnecting), Red (offline).
 *
 * @param {Object} props
 * @param {'connected'|'reconnecting'|'offline'} props.status
 */
export function ConnectionBadge({ status = "offline" }) {
  const config = {
    connected: {
      label: "LIVE STREAM",
      className: "status-connected",
    },
    reconnecting: {
      label: "RECONNECTING",
      className: "status-reconnecting",
    },
    offline: {
      label: "STREAM OFFLINE",
      className: "status-offline",
    },
  };

  const current = config[status] || config.offline;

  return (
    <div className={`connection-badge ${current.className}`} data-testid="connection-badge">
      <span className="pulsing-dot"></span>
      <span className="badge-text">{current.label}</span>
    </div>
  );
}
