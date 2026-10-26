import React, { useState, useEffect, useMemo, useRef } from "react";
import { useLineStream } from "../realtime/useLineStream.js";
import { ConnectionBadge } from "../components/ConnectionBadge.jsx";
import { AlertsBanner } from "../components/AlertsBanner.jsx";
import { KpiCard } from "../components/KpiCard.jsx";
import { ProductCard } from "../components/ProductCard.jsx";
import { DefectsChart } from "../components/DefectsChart.jsx";
import { RejectsGallery } from "../components/RejectsGallery.jsx";

/**
 * Main real-time industrial QA conveyor dashboard.
 *
 * @param {Object} props
 * @param {Array<Object>} props.lines - List of lines from API
 * @param {number|string} props.selectedLineId - Currently selected production line
 */
export function LiveDashboard({ lines = [], selectedLineId }) {
  const currentLine = useMemo(() => {
    return lines.find((l) => Number(l.id) === Number(selectedLineId)) || lines[0] || null;
  }, [lines, selectedLineId]);

  const activeLineId = currentLine?.id || selectedLineId;
  const lineThreshold = currentLine?.defectRateThreshold ?? 0.08;
  const windowMinutes = currentLine?.alertWindowMinutes ?? 5;

  // Stream hook for the selected line
  const {
    connected,
    connectionStatus,
    lastInspections,
    liveStats,
    alerts,
    error,
    reload,
  } = useLineStream(activeLineId);

  // In-memory temporal history of defect rates across live updates (max 20 points)
  const [defectRateHistory, setDefectRateHistory] = useState([]);
  const lastRecordedRateRef = useRef(null);

  useEffect(() => {
    if (liveStats && typeof liveStats.defectRate === "number") {
      const nowTime = new Date().toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      });
      const ratePct = Number((liveStats.defectRate * 100).toFixed(1));

      // Append only if meaningful or first point
      setDefectRateHistory((prev) => {
        const next = [...prev, { time: nowTime, rate: ratePct }];
        return next.slice(-20); // Keep last 20 telemetry ticks
      });
      lastRecordedRateRef.current = ratePct;
    }
  }, [liveStats]);

  // Reset defect rate history when switching production lines
  useEffect(() => {
    setDefectRateHistory([]);
  }, [activeLineId]);

  // 1. KPI Counts
  const total = liveStats?.total ?? 0;
  const pass = liveStats?.pass ?? 0;
  const reject = liveStats?.reject ?? 0;
  const defectRate = liveStats?.defectRate ?? 0;
  const passYield = total > 0 ? ((pass / (pass + reject || 1)) * 100).toFixed(1) : "100.0";
  const isDefectBreached = defectRate > lineThreshold;

  // 2. Per-product breakdown: normalize default products (cap, card, biscuit)
  const productCardsData = useMemo(() => {
    const defaultTypes = ["cap", "card", "biscuit"];
    const productStatsMap = new Map();

    // Fill with empty defaults
    defaultTypes.forEach((pt) => {
      productStatsMap.set(pt, {
        productType: pt,
        total: 0,
        pass: 0,
        reject: 0,
        defectRate: 0,
      });
    });

    // Merge liveStats perProductType if available
    if (liveStats?.perProductType && Array.isArray(liveStats.perProductType)) {
      liveStats.perProductType.forEach((p) => {
        if (!p?.productType) return;
        const key = p.productType.toLowerCase();
        productStatsMap.set(key, {
          productType: key,
          total: p.total || 0,
          pass: p.pass || 0,
          reject: p.reject || 0,
          defectRate: p.defectRate || 0,
        });
      });
    }

    return Array.from(productStatsMap.values());
  }, [liveStats]);

  // 3. Defects distribution: extract from live inspections in memory
  const defectTypeData = useMemo(() => {
    const counts = {};

    // Collect from recent rejected inspections
    lastInspections.forEach((insp) => {
      if (insp.verdict === "REJECT" && insp.failedChecks) {
        insp.failedChecks.forEach((fc) => {
          if (!fc) return;
          const label = fc.toLowerCase().trim();
          counts[label] = (counts[label] || 0) + 1;
        });
      }
    });

    return Object.entries(counts)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  }, [lastInspections]);

  // 4. Filter recent rejected inspections for gallery
  const recentRejects = useMemo(() => {
    return lastInspections.filter((i) => i.verdict === "REJECT");
  }, [lastInspections]);

  return (
    <div className="live-dashboard-container">
      {/* 1. Header Strip */}
      <div className="dashboard-header-strip">
        <div className="strip-left">
          <ConnectionBadge status={connectionStatus} />
          <div className="strip-line-info">
            <h2 className="strip-line-name">
              {currentLine ? currentLine.name : `Line #${activeLineId || "1"}`}
            </h2>
            <span className="strip-window-tag">
              Rolling {windowMinutes}m Window
            </span>
          </div>
        </div>

        <div className="strip-right">
          <div className="telemetry-counter">
            <span className="telemetry-label">IN-MEMORY BUFFER:</span>
            <span className="telemetry-val">{lastInspections.length} / 50</span>
          </div>

          <button
            onClick={reload}
            className="btn-refresh"
            title="Reload initial REST snapshot"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.19" />
            </svg>
            <span>Sync</span>
          </button>
        </div>
      </div>

      {/* Network or connection warning alert */}
      {error && (
        <div className="dashboard-error-banner">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <span>Telemetry Notice: {error}. Operating in reconnect fallback mode.</span>
        </div>
      )}

      {/* 2. Active Quality Alerts Banner */}
      <AlertsBanner alerts={alerts} lineId={activeLineId} />

      {/* 3. KPI Cards Strip */}
      <section className="dashboard-section">
        <div className="kpi-cards-grid">
          <KpiCard
            title="TOTAL INSPECTED"
            value={total.toLocaleString()}
            subtitle={`${windowMinutes}m rolling evaluation`}
            type="total"
          />
          <KpiCard
            title="PASSED CONFORMING"
            value={pass.toLocaleString()}
            subtitle={`Overall Yield: ${passYield}%`}
            type="pass"
          />
          <KpiCard
            title="DEFECTIVE REJECTS"
            value={reject.toLocaleString()}
            subtitle={reject === 0 ? "Zero defects flagged" : `${reject} items rejected`}
            type="reject"
          />
          <KpiCard
            title="DEFECT RATE"
            value={`${(defectRate * 100).toFixed(1)}%`}
            subtitle={isDefectBreached ? "EXCEEDS TOLERANCE" : "Within specification limit"}
            type="rate"
            isExceeded={isDefectBreached}
            thresholdInfo={`Limit: ${(lineThreshold * 100).toFixed(1)}%`}
          />
        </div>
      </section>

      {/* 4. Per-Product Category Breakdown */}
      <section className="dashboard-section">
        <div className="section-header">
          <h3 className="section-title">Product Classification Telemetry</h3>
          <span className="section-subtitle">Real-time yields by category</span>
        </div>
        <div className="product-cards-grid">
          {productCardsData.map((item) => (
            <ProductCard
              key={item.productType}
              productType={item.productType}
              total={item.total}
              pass={item.pass}
              reject={item.reject}
              defectRate={item.defectRate}
            />
          ))}
        </div>
      </section>

      {/* 5. Defect Analysis & Trend Visualizations */}
      <section className="dashboard-section">
        <DefectsChart
          defectTypeData={defectTypeData}
          defectRateHistory={defectRateHistory}
          threshold={lineThreshold}
        />
      </section>

      {/* 6. Recent Defective Rejects Gallery */}
      <section className="dashboard-section">
        <div className="section-header">
          <div className="section-title-group">
            <h3 className="section-title">Recent Flagged Rejections</h3>
            <span className="section-subtitle">
              Visual optical snapshots & triggered flaw rules ({recentRejects.length})
            </span>
          </div>
        </div>
        <RejectsGallery rejects={recentRejects} />
      </section>

      {/* 7. Live Realtime Conveyor Feed Table */}
      <section className="dashboard-section">
        <div className="section-header">
          <h3 className="section-title">Conveyor Live Inspection Stream</h3>
          <span className="section-subtitle">Newest events broadcast via WebSocket</span>
        </div>

        <div className="stream-table-card">
          {lastInspections.length === 0 ? (
            <div className="table-empty-state">
              <span className="spinner-mini"></span>
              <span>Waiting for initial conveyor trigger stream events...</span>
            </div>
          ) : (
            <div className="table-responsive">
              <table className="stream-table">
                <thead>
                  <tr>
                    <th>STATUS</th>
                    <th>TRACK ID</th>
                    <th>PRODUCT</th>
                    <th>CONFIDENCE</th>
                    <th>FAILED CHECKS</th>
                    <th>GEOMETRY (CIR/AR)</th>
                    <th>INSPECTED AT</th>
                  </tr>
                </thead>
                <tbody>
                  {lastInspections.slice(0, 15).map((item) => {
                    const isPass = item.verdict === "PASS";
                    const isReject = item.verdict === "REJECT";
                    const statusClass = isPass
                      ? "verdict-pass"
                      : isReject
                      ? "verdict-reject"
                      : "verdict-unknown";

                    const timeStr = item.inspectedAt
                      ? new Date(item.inspectedAt).toLocaleTimeString()
                      : "—";

                    return (
                      <tr key={item.id || item.trackId} className="stream-row">
                        <td>
                          <span className={`verdict-pill ${statusClass}`}>
                            {item.verdict || "UNKNOWN"}
                          </span>
                        </td>
                        <td className="font-mono">#{item.trackId}</td>
                        <td className="product-col">
                          {item.productType ? item.productType.toUpperCase() : "UNKNOWN"}
                        </td>
                        <td>
                          {item.confidence > 0
                            ? `${(item.confidence * 100).toFixed(1)}%`
                            : "—"}
                        </td>
                        <td>
                          {item.failedChecks && item.failedChecks.length > 0 ? (
                            <div className="table-failed-checks">
                              {item.failedChecks.map((f, i) => (
                                <span key={i} className="failed-check-mini">
                                  {f}
                                </span>
                              ))}
                            </div>
                          ) : (
                            <span className="text-muted">None</span>
                          )}
                        </td>
                        <td className="font-mono text-muted">
                          {item.circularity != null ? item.circularity.toFixed(2) : "—"} /{" "}
                          {item.aspectRatio != null ? item.aspectRatio.toFixed(2) : "—"}
                        </td>
                        <td className="text-muted">{timeStr}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
