import React, { useState, useEffect, useCallback, useMemo } from "react";
import { useAuth } from "../auth/AuthContext.jsx";
import { hasRole } from "../auth/roles.js";
import {
  getBatchesApi,
  getBatchStatsApi,
  startBatchApi,
  closeBatchApi,
  getHourlyStatsApi,
  getShiftReportsApi,
  generateShiftReportApi,
} from "../api/endpoints.js";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  CartesianGrid,
  Legend,
} from "recharts";
import { StatusBadge } from "../components/common/StatusBadge.jsx";
import { ConfirmDialog } from "../components/common/ConfirmDialog.jsx";
import { ErrorBanner } from "../components/common/ErrorBanner.jsx";
import { Spinner } from "../components/common/Spinner.jsx";
import { EmptyState } from "../components/common/EmptyState.jsx";

export function ReportsPage({ selectedLineId }) {
  const { user } = useAuth();
  const canManage = hasRole(user?.role, "SUPERVISOR");

  // -------------------------------------------------------------
  // 1. BATCH SECTION STATE
  // -------------------------------------------------------------
  const [batches, setBatches] = useState([]);
  const [selectedBatchId, setSelectedBatchId] = useState("");
  const [batchStats, setBatchStats] = useState(null);
  const [loadingBatches, setLoadingBatches] = useState(false);
  const [loadingBatchStats, setLoadingBatchStats] = useState(false);
  const [batchError, setBatchError] = useState(null);

  // New batch modal state
  const [showNewBatchModal, setShowNewBatchModal] = useState(false);
  const [newBatchName, setNewBatchName] = useState("");
  const [startingBatch, setStartingBatch] = useState(false);
  const [closeConfirmBatch, setCloseConfirmBatch] = useState(null);
  const [closingBatch, setClosingBatch] = useState(false);

  // Load batches list for active line
  const loadBatches = useCallback(async () => {
    if (!selectedLineId) return;
    setLoadingBatches(true);
    setBatchError(null);
    try {
      const data = await getBatchesApi(selectedLineId);
      const list = Array.isArray(data) ? data : [];
      setBatches(list);

      // Select first open batch or first batch if none selected
      if (list.length > 0) {
        setSelectedBatchId((prev) => {
          const exists = list.some((b) => String(b.id) === String(prev));
          if (exists) return prev;
          const openOne = list.find((b) => b.status === "OPEN" || !b.endedAt);
          return String(openOne ? openOne.id : list[0].id);
        });
      } else {
        setSelectedBatchId("");
        setBatchStats(null);
      }
    } catch (err) {
      setBatchError(err.message || "Failed to load batches");
    } finally {
      setLoadingBatches(false);
    }
  }, [selectedLineId]);

  useEffect(() => {
    loadBatches();
  }, [loadBatches]);

  // Load stats for selected batch
  useEffect(() => {
    if (!selectedBatchId) {
      setBatchStats(null);
      return;
    }

    let active = true;
    setLoadingBatchStats(true);
    getBatchStatsApi(selectedBatchId)
      .then((stats) => {
        if (active) setBatchStats(stats);
      })
      .catch((err) => {
        if (active) setBatchError("Batch statistics unavailable: " + err.message);
      })
      .finally(() => {
        if (active) setLoadingBatchStats(false);
      });

    return () => {
      active = false;
    };
  }, [selectedBatchId]);

  const selectedBatchObj = useMemo(() => {
    return batches.find((b) => String(b.id) === String(selectedBatchId)) || null;
  }, [batches, selectedBatchId]);

  // Handle starting a new batch
  const handleStartBatch = async (e) => {
    e.preventDefault();
    if (!newBatchName.trim()) return;
    setStartingBatch(true);
    setBatchError(null);
    try {
      const created = await startBatchApi({
        lineId: Number(selectedLineId),
        name: newBatchName.trim(),
      });
      setShowNewBatchModal(false);
      setNewBatchName("");
      await loadBatches();
      setSelectedBatchId(String(created.id));
    } catch (err) {
      setBatchError(err.message || "Failed to start new batch");
    } finally {
      setStartingBatch(false);
    }
  };

  // Handle closing the active batch
  const handleCloseBatch = async () => {
    if (!closeConfirmBatch) return;
    setClosingBatch(true);
    setBatchError(null);
    try {
      await closeBatchApi(closeConfirmBatch.id);
      setCloseConfirmBatch(null);
      await loadBatches();
    } catch (err) {
      setBatchError(err.message || "Failed to close batch");
    } finally {
      setClosingBatch(false);
    }
  };

  // Format defects by type for bar chart
  const batchDefectsChartData = useMemo(() => {
    if (!batchStats?.defectsByType) return [];
    return Object.entries(batchStats.defectsByType)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  }, [batchStats]);

  // -------------------------------------------------------------
  // 2. HOURLY TELEMETRY SECTION STATE
  // -------------------------------------------------------------
  const [hourlyDate, setHourlyDate] = useState(() => {
    const today = new Date();
    return today.toISOString().slice(0, 10);
  });
  const [hourlyData, setHourlyData] = useState([]);
  const [loadingHourly, setLoadingHourly] = useState(false);
  const [hourlyError, setHourlyError] = useState(null);

  const loadHourlyStats = useCallback(async () => {
    if (!selectedLineId || !hourlyDate) return;
    setLoadingHourly(true);
    setHourlyError(null);
    try {
      const data = await getHourlyStatsApi(selectedLineId, hourlyDate);
      setHourlyData(Array.isArray(data) ? data : []);
    } catch (err) {
      setHourlyError(err.message || "Failed to fetch hourly telemetry");
    } finally {
      setLoadingHourly(false);
    }
  }, [selectedLineId, hourlyDate]);

  useEffect(() => {
    loadHourlyStats();
  }, [loadHourlyStats]);

  const formattedHourlyChart = useMemo(() => {
    return hourlyData.map((d) => ({
      hourLabel: `${String(d.hour).padStart(2, "0")}:00`,
      pass: d.pass || 0,
      reject: d.reject || 0,
    }));
  }, [hourlyData]);

  // -------------------------------------------------------------
  // 3. SHIFT REPORTS SECTION STATE
  // -------------------------------------------------------------
  const [shiftReports, setShiftReports] = useState([]);
  const [loadingShifts, setLoadingShifts] = useState(false);
  const [shiftError, setShiftError] = useState(null);

  // Generate shift report form state
  const [shiftFrom, setShiftFrom] = useState("");
  const [shiftTo, setShiftTo] = useState("");
  const [generatingShift, setGeneratingShift] = useState(false);
  const [partialNotice, setPartialNotice] = useState(null);

  const loadShiftReports = useCallback(async () => {
    if (!selectedLineId) return;
    setLoadingShifts(true);
    setShiftError(null);
    try {
      const res = await getShiftReportsApi({ lineId: selectedLineId, size: 20 });
      setShiftReports(res.content || []);
    } catch (err) {
      setShiftError(err.message || "Failed to load shift reports");
    } finally {
      setLoadingShifts(false);
    }
  }, [selectedLineId]);

  useEffect(() => {
    loadShiftReports();
  }, [loadShiftReports]);

  const handleGenerateShiftReport = async (e) => {
    e.preventDefault();
    if (!shiftFrom || !shiftTo) {
      alert("Please specify both Start and End times.");
      return;
    }

    setGeneratingShift(true);
    setShiftError(null);
    setPartialNotice(null);

    try {
      const fromIso = new Date(shiftFrom).toISOString();
      const toIso = new Date(shiftTo).toISOString();
      const result = await generateShiftReportApi(selectedLineId, fromIso, toIso);

      if (result.partial) {
        setPartialNotice(
          "Notice: The selected period has not finished yet, so this summary was generated for preview and is not permanently saved in the database."
        );
      }

      await loadShiftReports();
    } catch (err) {
      setShiftError(err.message || "Failed to generate shift summary");
    } finally {
      setGeneratingShift(false);
    }
  };

  return (
    <div className="page-container reports-page-container">
      <div className="page-header-strip">
        <div>
          <h2 className="page-title">Shift Summaries & Performance Reports</h2>
          <span className="page-subtitle text-muted">
            Batch tracking, 24-hour throughput charts, and operational shift audits
          </span>
        </div>
      </div>

      {/* ========================================================= */}
      {/* SECTION 1: BATCH QUALITY TELEMETRY                        */}
      {/* ========================================================= */}
      <section className="dashboard-section batch-section-card">
        <div className="section-header">
          <div>
            <h3 className="section-title">Batch QA Performance</h3>
            <span className="section-subtitle">Lot-specific yield analytics and flaw breakdown</span>
          </div>

          <div className="batch-controls-group">
            <div className="filter-group">
              <label htmlFor="batch-select" className="filter-label">
                SELECT BATCH:
              </label>
              <select
                id="batch-select"
                className="filter-select"
                value={selectedBatchId}
                onChange={(e) => setSelectedBatchId(e.target.value)}
                disabled={loadingBatches}
                data-testid="select-batch"
              >
                {batches.length === 0 ? (
                  <option value="">No batches on this line</option>
                ) : (
                  batches.map((b) => (
                    <option key={b.id} value={b.id}>
                      #{b.id} — {b.name} ({b.status || (b.endedAt ? "CLOSED" : "OPEN")})
                    </option>
                  ))
                )}
              </select>
            </div>

            {canManage && (
              <>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => setShowNewBatchModal(true)}
                  data-testid="btn-start-batch-modal"
                >
                  + Start New Batch
                </button>

                {selectedBatchObj &&
                  (selectedBatchObj.status === "OPEN" || !selectedBatchObj.endedAt) && (
                    <button
                      type="button"
                      className="btn-danger-outline"
                      onClick={() => setCloseConfirmBatch(selectedBatchObj)}
                      data-testid="btn-close-batch"
                    >
                      Close Active Batch
                    </button>
                  )}
              </>
            )}
          </div>
        </div>

        {batchError && <ErrorBanner message={batchError} onDismiss={() => setBatchError(null)} />}

        {loadingBatchStats ? (
          <Spinner size="md" label="Loading batch telemetry..." centered />
        ) : !batchStats ? (
          <EmptyState
            title="No Batch Selected"
            description="Select or start a production batch to view metrics."
          />
        ) : (
          <div className="batch-stats-dashboard">
            {/* KPI Cards Strip */}
            <div className="batch-kpis-grid">
              <div className="kpi-mini-card">
                <span className="kpi-mini-label">TOTAL INSPECTED</span>
                <span className="kpi-mini-val font-mono">{batchStats.total || 0}</span>
              </div>
              <div className="kpi-mini-card">
                <span className="kpi-mini-label">CONFORMING PASS</span>
                <span className="kpi-mini-val font-mono text-success">{batchStats.pass || 0}</span>
              </div>
              <div className="kpi-mini-card">
                <span className="kpi-mini-label">REJECT DEFECTIVE</span>
                <span className="kpi-mini-val font-mono text-danger">{batchStats.reject || 0}</span>
              </div>
              <div className="kpi-mini-card">
                <span className="kpi-mini-label">DEFECT RATE</span>
                <span className="kpi-mini-val font-mono text-danger">
                  {((batchStats.defectRate || 0) * 100).toFixed(1)}%
                </span>
              </div>
            </div>

            {/* Charts & Product Grid */}
            <div className="batch-charts-grid">
              {/* Defects by Type Bar Chart */}
              <div className="chart-card-box">
                <h4 className="chart-box-title">Defects by Category</h4>
                {batchDefectsChartData.length === 0 ? (
                  <div className="chart-empty-message">Zero defects flagged in this batch</div>
                ) : (
                  <div style={{ width: "100%", height: 240 }}>
                    <ResponsiveContainer>
                      <BarChart data={batchDefectsChartData}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#262f40" />
                        <XAxis dataKey="name" stroke="#94a3b8" />
                        <YAxis stroke="#94a3b8" />
                        <Tooltip
                          contentStyle={{
                            backgroundColor: "#111827",
                            borderColor: "#374151",
                            borderRadius: "6px",
                          }}
                        />
                        <Bar dataKey="count" fill="#ef4444" radius={[4, 4, 0, 0]} />
                      </BarChart>
                    </ResponsiveContainer>
                  </div>
                )}
              </div>

              {/* Counts Per Product Type Table */}
              <div className="chart-card-box">
                <h4 className="chart-box-title">Counts per Product Category</h4>
                {!batchStats.countsPerProductType ||
                Object.keys(batchStats.countsPerProductType).length === 0 ? (
                  <div className="chart-empty-message">No item classification data available</div>
                ) : (
                  <div className="table-responsive">
                    <table className="stream-table">
                      <thead>
                        <tr>
                          <th>PRODUCT</th>
                          <th>ITEMS INSPECTED</th>
                          <th>SHARE</th>
                        </tr>
                      </thead>
                      <tbody>
                        {Object.entries(batchStats.countsPerProductType).map(([type, count]) => {
                          const total = batchStats.total || 1;
                          const sharePct = ((count / total) * 100).toFixed(1);
                          return (
                            <tr key={type}>
                              <td className="font-mono">{type.toUpperCase()}</td>
                              <td className="font-mono">{count}</td>
                              <td className="font-mono text-muted">{sharePct}%</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </section>

      {/* ========================================================= */}
      {/* SECTION 2: 24-HOUR HOURLY THROUGHPUT                      */}
      {/* ========================================================= */}
      <section className="dashboard-section hourly-section-card">
        <div className="section-header">
          <div>
            <h3 className="section-title">24-Hour Throughput & Defect Trend</h3>
            <span className="section-subtitle">
              Stacked hourly inspection volume (PASS vs REJECT)
            </span>
          </div>

          <div className="filter-group">
            <label htmlFor="hourly-date-picker" className="filter-label">
              DATE:
            </label>
            <input
              id="hourly-date-picker"
              type="date"
              className="filter-input"
              value={hourlyDate}
              onChange={(e) => setHourlyDate(e.target.value)}
              data-testid="hourly-date-picker"
            />
          </div>
        </div>

        {hourlyError && <ErrorBanner message={hourlyError} onRetry={loadHourlyStats} />}

        {loadingHourly ? (
          <Spinner size="md" label="Loading hourly stats..." centered />
        ) : (
          <div style={{ width: "100%", height: 300, marginTop: "1rem" }}>
            <ResponsiveContainer>
              <BarChart data={formattedHourlyChart}>
                <CartesianGrid strokeDasharray="3 3" stroke="#262f40" />
                <XAxis dataKey="hourLabel" stroke="#94a3b8" />
                <YAxis stroke="#94a3b8" />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "#111827",
                    borderColor: "#374151",
                    borderRadius: "6px",
                  }}
                />
                <Legend />
                <Bar dataKey="pass" name="Conforming (Pass)" stackId="a" fill="#10b981" />
                <Bar dataKey="reject" name="Defective (Reject)" stackId="a" fill="#ef4444" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </section>

      {/* ========================================================= */}
      {/* SECTION 3: SHIFT SUMMARIES & AUDITS                       */}
      {/* ========================================================= */}
      <section className="dashboard-section shift-section-card">
        <div className="section-header">
          <div>
            <h3 className="section-title">Shift Audit Reports</h3>
            <span className="section-subtitle">Aggregated shift quality metrics</span>
          </div>
        </div>

        {partialNotice && (
          <div className="readonly-notice-banner partial-notice" data-testid="partial-shift-notice">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="10" />
              <line x1="12" y1="8" x2="12" y2="12" />
              <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            <span>{partialNotice}</span>
          </div>
        )}

        {shiftError && <ErrorBanner message={shiftError} onRetry={loadShiftReports} />}

        {/* Generate Shift Report Form (SUPERVISOR+) */}
        {canManage && (
          <form onSubmit={handleGenerateShiftReport} className="generate-shift-form">
            <div className="shift-form-row">
              <div className="filter-group">
                <label htmlFor="shift-from" className="filter-label">
                  Period Start:
                </label>
                <input
                  id="shift-from"
                  type="datetime-local"
                  className="filter-input"
                  value={shiftFrom}
                  onChange={(e) => setShiftFrom(e.target.value)}
                  required
                  data-testid="shift-from-input"
                />
              </div>

              <div className="filter-group">
                <label htmlFor="shift-to" className="filter-label">
                  Period End:
                </label>
                <input
                  id="shift-to"
                  type="datetime-local"
                  className="filter-input"
                  value={shiftTo}
                  onChange={(e) => setShiftTo(e.target.value)}
                  required
                  data-testid="shift-to-input"
                />
              </div>

              <button
                type="submit"
                className="btn-primary"
                disabled={generatingShift}
                data-testid="btn-generate-shift"
              >
                {generatingShift ? "Calculating Shift..." : "Generate Shift Report"}
              </button>
            </div>
          </form>
        )}

        {/* Shift Reports List Table */}
        {loadingShifts ? (
          <Spinner size="md" label="Loading shift summaries..." centered />
        ) : shiftReports.length === 0 ? (
          <EmptyState
            title="No Shift Summaries Found"
            description="No shift summaries recorded for this production line yet."
          />
        ) : (
          <div className="table-responsive" style={{ marginTop: "1rem" }}>
            <table className="stream-table" data-testid="shifts-table">
              <thead>
                <tr>
                  <th>SHIFT</th>
                  <th>PERIOD</th>
                  <th>TOTAL</th>
                  <th>PASS</th>
                  <th>REJECT</th>
                  <th>DEFECT RATE</th>
                  <th>STATUS</th>
                </tr>
              </thead>
              <tbody>
                {shiftReports.map((s) => {
                  const startStr = s.periodStart
                    ? new Date(s.periodStart).toLocaleTimeString([], {
                        hour: "2-digit",
                        minute: "2-digit",
                      })
                    : "—";
                  const endStr = s.periodEnd
                    ? new Date(s.periodEnd).toLocaleTimeString([], {
                        hour: "2-digit",
                        minute: "2-digit",
                      })
                    : "—";
                  const dateStr = s.periodStart
                    ? new Date(s.periodStart).toLocaleDateString()
                    : "";

                  return (
                    <tr key={s.id || `${s.periodStart}-${s.periodEnd}`}>
                      <td className="font-mono font-bold">{s.shiftName || "Shift"}</td>
                      <td className="font-mono text-sm">
                        {dateStr} ({startStr} – {endStr})
                      </td>
                      <td className="font-mono">{s.total}</td>
                      <td className="font-mono text-success">{s.pass}</td>
                      <td className="font-mono text-danger">{s.reject}</td>
                      <td className="font-mono font-bold text-danger">
                        {((s.defectRate || 0) * 100).toFixed(1)}%
                      </td>
                      <td>
                        {s.partial ? (
                          <span className="badge-warning font-mono text-xs p-1">PARTIAL</span>
                        ) : (
                          <StatusBadge status="CLOSED" label="FINALIZED" size="sm" />
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Start New Batch Modal */}
      {showNewBatchModal && (
        <div className="modal-overlay" role="presentation" onClick={() => setShowNewBatchModal(false)}>
          <div
            className="modal-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="new-batch-title"
            data-testid="modal-start-batch"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="modal-header">
              <h3 id="new-batch-title" className="modal-title">
                Start New Production Batch
              </h3>
              <button
                type="button"
                className="btn-modal-close"
                onClick={() => setShowNewBatchModal(false)}
              >
                ✕
              </button>
            </div>
            <form onSubmit={handleStartBatch}>
              <div className="modal-body">
                <div className="form-group">
                  <label htmlFor="new-batch-name" className="form-label">
                    Batch Identifier / Name:
                  </label>
                  <input
                    id="new-batch-name"
                    type="text"
                    className="form-input"
                    placeholder="e.g. Batch-20261026-Alpha"
                    value={newBatchName}
                    onChange={(e) => setNewBatchName(e.target.value)}
                    required
                    autoFocus
                    data-testid="input-new-batch-name"
                  />
                </div>
              </div>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setShowNewBatchModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={startingBatch || !newBatchName.trim()}
                  data-testid="submit-start-batch"
                >
                  {startingBatch ? "Starting..." : "Start Batch"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Confirm Close Active Batch Dialog */}
      <ConfirmDialog
        isOpen={Boolean(closeConfirmBatch)}
        title={`Close Production Batch #${closeConfirmBatch?.id}?`}
        message={`Are you sure you want to close batch "${closeConfirmBatch?.name}"? Items subsequently inspected will not be assigned to this batch.`}
        confirmLabel="Close Batch"
        variant="danger"
        loading={closingBatch}
        onConfirm={handleCloseBatch}
        onCancel={() => setCloseConfirmBatch(null)}
      />
    </div>
  );
}
