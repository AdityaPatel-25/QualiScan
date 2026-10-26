import React, { useState, useEffect, useCallback, useRef } from "react";
import { useAuth } from "../auth/AuthContext.jsx";
import { hasRole } from "../auth/roles.js";
import { getAlertsApi, acknowledgeAlertApi, resolveAlertApi } from "../api/endpoints.js";
import { getBrokerUrl } from "../realtime/wsUrl.js";
import { Client } from "@stomp/stompjs";
import { StatusBadge } from "../components/common/StatusBadge.jsx";
import { ConfirmDialog } from "../components/common/ConfirmDialog.jsx";
import { ErrorBanner } from "../components/common/ErrorBanner.jsx";
import { EmptyState } from "../components/common/EmptyState.jsx";
import { Spinner } from "../components/common/Spinner.jsx";

/**
 * Quality Incidents & Defect Rate Alerts Control Center.
 */
export function AlertsPage({ selectedLineId }) {
  const { user, token, isAuthenticated } = useAuth();
  const canManageAlerts = hasRole(user?.role, "SUPERVISOR");

  const [statusFilter, setStatusFilter] = useState("ALL");
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [actionError, setActionError] = useState(null);
  const [pageError, setPageError] = useState(null);

  // Dialog action state
  const [confirmAction, setConfirmAction] = useState(null); // { type: 'acknowledge'|'resolve', alert: Object }
  const [submittingAction, setSubmittingAction] = useState(false);

  // Fetch alerts from REST API
  const loadAlerts = useCallback(async () => {
    setLoading(true);
    setPageError(null);
    try {
      const res = await getAlertsApi({
        lineId: selectedLineId || undefined,
        status: statusFilter === "ALL" ? undefined : statusFilter,
        page: 0,
        size: 50,
      });
      setAlerts(res.content || []);
    } catch (err) {
      setPageError(err.message || "Failed to load quality alerts");
    } finally {
      setLoading(false);
    }
  }, [selectedLineId, statusFilter]);

  useEffect(() => {
    loadAlerts();
  }, [loadAlerts]);

  // STOMP Live Subscription to /topic/alerts
  const stompClientRef = useRef(null);
  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const brokerURL = getBrokerUrl();
    const client = new Client({
      brokerURL,
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe("/topic/alerts", (frame) => {
          try {
            const incoming = JSON.parse(frame.body);
            if (!incoming?.id) return;

            setAlerts((prev) => {
              // Check if matching line filter
              if (selectedLineId && incoming.lineId && Number(incoming.lineId) !== Number(selectedLineId)) {
                return prev;
              }

              // Check if matching status filter
              if (statusFilter !== "ALL" && incoming.status !== statusFilter) {
                // Remove if it no longer matches status filter
                return prev.filter((a) => a.id !== incoming.id);
              }

              const existsIndex = prev.findIndex((a) => a.id === incoming.id);
              if (existsIndex >= 0) {
                const next = [...prev];
                next[existsIndex] = { ...next[existsIndex], ...incoming };
                return next;
              } else {
                return [incoming, ...prev];
              }
            });
          } catch (e) {
            console.warn("Failed to parse incoming live alert:", e);
          }
        });
      },
    });

    stompClientRef.current = client;
    client.activate();

    return () => {
      if (stompClientRef.current) {
        stompClientRef.current.deactivate();
        stompClientRef.current = null;
      }
    };
  }, [token, isAuthenticated, selectedLineId, statusFilter]);

  // Handle Acknowledge Alert execution
  const handleAcknowledge = async (alertId) => {
    setSubmittingAction(true);
    setActionError(null);
    try {
      const updated = await acknowledgeAlertApi(alertId);
      setAlerts((prev) =>
        prev.map((a) => (a.id === alertId ? { ...a, ...updated } : a))
      );
      setConfirmAction(null);
    } catch (err) {
      setActionError(err.message || "Failed to acknowledge alert");
    } finally {
      setSubmittingAction(false);
    }
  };

  // Handle Resolve Alert execution
  const handleResolve = async (alertId) => {
    setSubmittingAction(true);
    setActionError(null);
    try {
      const updated = await resolveAlertApi(alertId);
      setAlerts((prev) =>
        prev.map((a) => (a.id === alertId ? { ...a, ...updated } : a))
      );
      setConfirmAction(null);
    } catch (err) {
      setActionError(err.message || "Failed to resolve alert");
    } finally {
      setSubmittingAction(false);
    }
  };

  return (
    <div className="page-container alerts-page-container">
      {/* Header */}
      <div className="page-header-strip">
        <div>
          <h2 className="page-title">Quality Incidents & Alerts</h2>
          <span className="page-subtitle text-muted">
            Live defect rate breach monitors and operator intervention log
          </span>
        </div>

        <div className="status-filter-pills">
          {["ALL", "OPEN", "ACKNOWLEDGED", "RESOLVED"].map((s) => (
            <button
              key={s}
              type="button"
              className={`filter-pill-btn ${statusFilter === s ? "active" : ""}`}
              onClick={() => setStatusFilter(s)}
              data-testid={`filter-status-${s.toLowerCase()}`}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      {/* Notifications */}
      {pageError && <ErrorBanner message={pageError} onRetry={loadAlerts} />}
      {actionError && <ErrorBanner message={actionError} onDismiss={() => setActionError(null)} />}

      {!canManageAlerts && (
        <div className="readonly-notice-banner" data-testid="readonly-notice">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span>Operator Mode: Viewing incident records in read-only mode. Incident acknowledgment & resolution require Supervisor privileges.</span>
        </div>
      )}

      {/* Alerts List */}
      {loading ? (
        <Spinner size="lg" label="Loading quality alerts..." centered />
      ) : alerts.length === 0 ? (
        <EmptyState
          title="No Quality Alerts"
          description={`No ${statusFilter !== "ALL" ? statusFilter : ""} quality incidents recorded for this production line.`}
        />
      ) : (
        <div className="alerts-cards-list" data-testid="alerts-list">
          {alerts.map((alert) => {
            const isResolved = alert.status === "RESOLVED";
            const isAcknowledged = alert.status === "ACKNOWLEDGED";
            const isOpen = alert.status === "OPEN";

            const defectRatePct = (alert.defectRate * 100).toFixed(1);
            const thresholdPct = (alert.threshold * 100).toFixed(1);
            const targetProduct = alert.productType
              ? alert.productType.toUpperCase()
              : "Whole line";

            return (
              <div
                key={alert.id}
                className={`alert-incident-card ${
                  isOpen ? "incident-open" : isAcknowledged ? "incident-ack" : "incident-resolved"
                }`}
                data-testid={`alert-item-${alert.id}`}
              >
                <div className="incident-header">
                  <div className="incident-badges">
                    <StatusBadge status={alert.status} />
                    <span className="incident-id font-mono">ALERT #{alert.id}</span>
                    <span className="incident-type-tag">{alert.type}</span>
                  </div>

                  <div className="incident-time text-muted text-sm font-mono">
                    Created: {alert.createdAt ? new Date(alert.createdAt).toLocaleString() : "—"}
                  </div>
                </div>

                <div className="incident-body">
                  <p className="incident-message">{alert.message}</p>

                  <div className="incident-stats-grid">
                    <div className="incident-stat">
                      <span className="stat-label">TARGET PRODUCT</span>
                      <span className="stat-value font-mono">{targetProduct}</span>
                    </div>

                    <div className="incident-stat">
                      <span className="stat-label">DEFECT RATE</span>
                      <span className="stat-value font-mono text-danger">
                        {defectRatePct}%{" "}
                        <span className="text-muted text-sm">/ {thresholdPct}% threshold</span>
                      </span>
                    </div>

                    <div className="incident-stat">
                      <span className="stat-label">EVALUATED SAMPLE</span>
                      <span className="stat-value font-mono">{alert.sampleSize} items</span>
                    </div>

                    <div className="incident-stat">
                      <span className="stat-label">ROLLING WINDOW</span>
                      <span className="stat-value font-mono">{alert.windowMinutes} min</span>
                    </div>
                  </div>

                  {/* Audit Trail Details */}
                  {(alert.acknowledgedBy || alert.acknowledgedAt || alert.resolvedAt) && (
                    <div className="incident-audit-trail font-mono text-xs text-muted">
                      {alert.acknowledgedBy && (
                        <span>
                          Acknowledged by <strong className="text-white">{alert.acknowledgedBy}</strong> at{" "}
                          {new Date(alert.acknowledgedAt).toLocaleTimeString()}
                        </span>
                      )}
                      {alert.resolvedAt && (
                        <span style={{ marginLeft: alert.acknowledgedBy ? "1.5rem" : 0 }}>
                          Resolved at {new Date(alert.resolvedAt).toLocaleTimeString()}
                        </span>
                      )}
                    </div>
                  )}
                </div>

                {/* Supervisor Actions */}
                {canManageAlerts && !isResolved && (
                  <div className="incident-footer-actions">
                    {isOpen && (
                      <button
                        type="button"
                        className="btn-warning-sm"
                        onClick={() =>
                          setConfirmAction({
                            type: "acknowledge",
                            alert,
                          })
                        }
                        data-testid={`btn-ack-${alert.id}`}
                      >
                        Acknowledge Alert
                      </button>
                    )}

                    <button
                      type="button"
                      className="btn-success-sm"
                      onClick={() =>
                        setConfirmAction({
                          type: "resolve",
                          alert,
                        })
                      }
                      data-testid={`btn-resolve-${alert.id}`}
                    >
                      Resolve Alert
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Confirmation Dialog for Acknowledge or Resolve */}
      <ConfirmDialog
        isOpen={Boolean(confirmAction)}
        title={
          confirmAction?.type === "acknowledge"
            ? `Acknowledge Quality Alert #${confirmAction?.alert?.id}`
            : `Resolve Quality Alert #${confirmAction?.alert?.id}`
        }
        message={
          confirmAction?.type === "acknowledge"
            ? `Are you sure you want to acknowledge Alert #${confirmAction?.alert?.id}? This marks the incident as under active operator investigation.`
            : `Are you sure you want to resolve Alert #${confirmAction?.alert?.id}? This closes the active incident.`
        }
        confirmLabel={
          confirmAction?.type === "acknowledge" ? "Acknowledge Incident" : "Resolve Incident"
        }
        variant={confirmAction?.type === "acknowledge" ? "warning" : "primary"}
        loading={submittingAction}
        onConfirm={() => {
          if (confirmAction?.type === "acknowledge") {
            handleAcknowledge(confirmAction.alert.id);
          } else if (confirmAction?.type === "resolve") {
            handleResolve(confirmAction.alert.id);
          }
        }}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  );
}
