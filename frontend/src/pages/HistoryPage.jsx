import React, { useState, useEffect, useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import { getInspectionsApi } from "../api/endpoints.js";
import { DataTable } from "../components/common/DataTable.jsx";
import { StatusBadge } from "../components/common/StatusBadge.jsx";
import { ErrorBanner } from "../components/common/ErrorBanner.jsx";
import { EmptyState } from "../components/common/EmptyState.jsx";
import { AuthImage } from "../components/AuthImage.jsx";

/**
 * Inspection detail modal displaying larger snapshot and geometric telemetry.
 */
function InspectionDetailModal({ inspection, onClose }) {
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  if (!inspection) return null;

  return (
    <div className="modal-overlay" role="presentation" onClick={onClose}>
      <div
        className="modal-dialog inspection-modal-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="inspection-detail-title"
        data-testid="inspection-detail-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <div>
            <h3 id="inspection-detail-title" className="modal-title">
              Inspection #{inspection.id}
            </h3>
            <span className="modal-subtitle text-muted">
              Track ID: <span className="font-mono">#{inspection.trackId}</span>
            </span>
          </div>
          <button
            type="button"
            className="btn-modal-close"
            onClick={onClose}
            aria-label="Close inspection modal"
          >
            ✕
          </button>
        </div>

        <div className="modal-body inspection-modal-body">
          <div className="inspection-modal-grid">
            {/* Snapshot Image Preview */}
            <div className="modal-snapshot-container">
              <AuthImage
                snapshotId={inspection.id}
                alt={`Snapshot #${inspection.id}`}
              />
            </div>

            {/* Telemetry Metrics & Quality Status */}
            <div className="modal-details-container">
              <div className="detail-item">
                <span className="detail-label">VERDICT</span>
                <StatusBadge status={inspection.verdict} size="md" />
              </div>

              <div className="detail-item">
                <span className="detail-label">PRODUCT TYPE</span>
                <span className="detail-value font-mono">
                  {(inspection.productType || "UNKNOWN").toUpperCase()}
                </span>
              </div>

              <div className="detail-item">
                <span className="detail-label">IDENTIFICATION CONFIDENCE</span>
                <span className="detail-value font-mono">
                  {inspection.confidence != null
                    ? `${(inspection.confidence * 100).toFixed(1)}%`
                    : "—"}
                </span>
              </div>

              <div className="detail-item">
                <span className="detail-label">BATCH</span>
                <span className="detail-value font-mono">
                  {inspection.batchId ? `Batch #${inspection.batchId}` : "Unassigned"}
                </span>
              </div>

              <div className="detail-item">
                <span className="detail-label">INSPECTED AT</span>
                <span className="detail-value">
                  {inspection.inspectedAt
                    ? new Date(inspection.inspectedAt).toLocaleString()
                    : "—"}
                </span>
              </div>

              {/* Physical Geometric Measurements */}
              <div className="measurements-section">
                <h5 className="measurements-header">Physical Measurements</h5>
                <div className="measurements-grid">
                  <div className="meas-box">
                    <span className="meas-label">AREA</span>
                    <span className="meas-val font-mono">
                      {inspection.area != null ? inspection.area.toFixed(1) : "—"} px²
                    </span>
                  </div>
                  <div className="meas-box">
                    <span className="meas-label">CIRCULARITY</span>
                    <span className="meas-val font-mono">
                      {inspection.circularity != null ? inspection.circularity.toFixed(3) : "—"}
                    </span>
                  </div>
                  <div className="meas-box">
                    <span className="meas-label">ASPECT RATIO</span>
                    <span className="meas-val font-mono">
                      {inspection.aspectRatio != null ? inspection.aspectRatio.toFixed(3) : "—"}
                    </span>
                  </div>
                </div>
              </div>

              {/* Failed Flaw Rules */}
              <div className="flaws-section">
                <h5 className="flaws-header">Triggered Defect Rules</h5>
                {inspection.failedChecks && inspection.failedChecks.length > 0 ? (
                  <div className="failed-checks-list">
                    {inspection.failedChecks.map((flaw, idx) => (
                      <span key={idx} className="flaw-pill">
                        {flaw}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="text-success text-sm">No defects detected (Conforming item)</span>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="modal-footer">
          <button type="button" className="btn-secondary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Historical Inspections Search & Analytics Page.
 */
export function HistoryPage({ selectedLineId }) {
  const [searchParams, setSearchParams] = useSearchParams();

  // Read initial filter values from URL query string
  const productType = searchParams.get("productType") || "";
  const verdict = searchParams.get("verdict") || "";
  const from = searchParams.get("from") || "";
  const to = searchParams.get("to") || "";
  const page = parseInt(searchParams.get("page") || "0", 10);
  const size = parseInt(searchParams.get("size") || "20", 10);

  const [inspections, setInspections] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [exporting, setExporting] = useState(false);
  const [selectedInspection, setSelectedInspection] = useState(null);

  // Helper to update search params while preserving unchanged params
  const updateParams = useCallback(
    (newParams) => {
      const current = Object.fromEntries(searchParams.entries());
      const merged = { ...current, ...newParams };

      // Remove empty entries to keep URL clean
      Object.keys(merged).forEach((k) => {
        if (merged[k] === "" || merged[k] == null) {
          delete merged[k];
        }
      });

      setSearchParams(merged);
    },
    [searchParams, setSearchParams]
  );

  // Fetch inspections from backend
  const fetchInspections = useCallback(async () => {
    setLoading(true);
    setError(null);

    try {
      // Send UTC ISO strings if from / to are present
      let fromIso = undefined;
      let toIso = undefined;
      if (from) {
        const d = new Date(from);
        if (!isNaN(d.getTime())) fromIso = d.toISOString();
      }
      if (to) {
        const d = new Date(to);
        if (!isNaN(d.getTime())) toIso = d.toISOString();
      }

      const res = await getInspectionsApi({
        lineId: selectedLineId || undefined,
        productType: productType || undefined,
        verdict: verdict || undefined,
        from: fromIso,
        to: toIso,
        page,
        size,
      });

      setInspections(res.content || []);
      setTotalPages(res.totalPages || 0);
      setTotalElements(res.totalElements || 0);
    } catch (err) {
      setError(err.message || "Failed to load inspection history");
    } finally {
      setLoading(false);
    }
  }, [selectedLineId, productType, verdict, from, to, page, size]);

  useEffect(() => {
    fetchInspections();
  }, [fetchInspections]);

  // Client-side CSV Export of filtered records (up to 1,000)
  const handleExportCsv = async () => {
    setExporting(true);
    try {
      let fromIso = undefined;
      let toIso = undefined;
      if (from) {
        const d = new Date(from);
        if (!isNaN(d.getTime())) fromIso = d.toISOString();
      }
      if (to) {
        const d = new Date(to);
        if (!isNaN(d.getTime())) toIso = d.toISOString();
      }

      const res = await getInspectionsApi({
        lineId: selectedLineId || undefined,
        productType: productType || undefined,
        verdict: verdict || undefined,
        from: fromIso,
        to: toIso,
        page: 0,
        size: 1000,
      });

      const records = res.content || [];
      if (records.length === 0) {
        alert("No inspection records to export matching current filters.");
        return;
      }

      const headers = [
        "ID",
        "Track ID",
        "Line ID",
        "Batch ID",
        "Product Type",
        "Verdict",
        "Confidence",
        "Area",
        "Circularity",
        "Aspect Ratio",
        "Failed Checks",
        "Inspected At (UTC)",
      ];

      const csvRows = [headers.join(",")];
      records.forEach((row) => {
        const failedStr = Array.isArray(row.failedChecks)
          ? row.failedChecks.join(";")
          : row.failedChecks || "";

        const values = [
          row.id,
          `"${row.trackId || ""}"`,
          row.lineId || "",
          row.batchId || "",
          row.productType || "",
          row.verdict || "",
          row.confidence != null ? row.confidence.toFixed(4) : "",
          row.area != null ? row.area.toFixed(2) : "",
          row.circularity != null ? row.circularity.toFixed(4) : "",
          row.aspectRatio != null ? row.aspectRatio.toFixed(4) : "",
          `"${failedStr.replace(/"/g, '""')}"`,
          `"${row.inspectedAt || ""}"`,
        ];
        csvRows.push(values.join(","));
      });

      const blob = new Blob([csvRows.join("\n")], { type: "text/csv;charset=utf-8;" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.setAttribute("href", url);
      link.setAttribute("download", `qualiscan_inspections_${Date.now()}.csv`);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch (err) {
      alert("Failed to export inspection CSV: " + err.message);
    } finally {
      setExporting(false);
    }
  };

  const columns = useMemo(
    () => [
      {
        header: "TIME",
        accessor: "inspectedAt",
        width: "160px",
        cell: (row) => (
          <span className="font-mono text-sm">
            {row.inspectedAt ? new Date(row.inspectedAt).toLocaleString() : "—"}
          </span>
        ),
      },
      {
        header: "TRACK ID",
        accessor: "trackId",
        width: "120px",
        cell: (row) => <span className="font-mono">#{row.trackId}</span>,
      },
      {
        header: "PRODUCT",
        accessor: "productType",
        width: "110px",
        cell: (row) => (
          <span className="product-type-cell font-mono">
            {(row.productType || "UNKNOWN").toUpperCase()}
          </span>
        ),
      },
      {
        header: "VERDICT",
        accessor: "verdict",
        width: "120px",
        cell: (row) => <StatusBadge status={row.verdict} size="sm" />,
      },
      {
        header: "FAILED CHECKS",
        accessor: "failedChecks",
        cell: (row) => {
          if (!row.failedChecks || row.failedChecks.length === 0) {
            return <span className="text-muted text-sm">None</span>;
          }
          return (
            <div className="table-flaws-wrapper">
              {row.failedChecks.map((flaw, idx) => (
                <span key={idx} className="flaw-pill-sm">
                  {flaw}
                </span>
              ))}
            </div>
          );
        },
      },
      {
        header: "CONFIDENCE",
        accessor: "confidence",
        width: "110px",
        cell: (row) => (
          <span className="font-mono text-sm">
            {row.confidence != null ? `${(row.confidence * 100).toFixed(1)}%` : "—"}
          </span>
        ),
      },
      {
        header: "SNAPSHOT",
        width: "90px",
        cell: (row) => (
          <div className="table-thumb-wrapper">
            <AuthImage snapshotId={row.id} alt="Thumb" />
          </div>
        ),
      },
    ],
    []
  );

  return (
    <div className="page-container history-page-container">
      {/* Page Header */}
      <div className="page-header-strip">
        <div>
          <h2 className="page-title">Inspection Records & Analytics</h2>
          <span className="page-subtitle text-muted">
            Historical conveyor logs, optical measurements & defect archives
          </span>
        </div>

        <button
          type="button"
          onClick={handleExportCsv}
          disabled={exporting || loading || inspections.length === 0}
          className="btn-secondary"
          data-testid="export-csv-button"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          <span>{exporting ? "Generating CSV..." : "Export Filtered CSV"}</span>
        </button>
      </div>

      {error && <ErrorBanner message={error} onRetry={fetchInspections} />}

      {/* Filter Toolbar */}
      <div className="filter-toolbar-card">
        <div className="filter-group">
          <label htmlFor="filter-product" className="filter-label">
            Product:
          </label>
          <select
            id="filter-product"
            className="filter-select"
            value={productType}
            onChange={(e) => updateParams({ productType: e.target.value, page: 0 })}
            data-testid="filter-product"
          >
            <option value="">All Products</option>
            <option value="cap">Bottle Cap</option>
            <option value="card">Printed Card</option>
            <option value="biscuit">Biscuit</option>
          </select>
        </div>

        <div className="filter-group">
          <label htmlFor="filter-verdict" className="filter-label">
            Verdict:
          </label>
          <select
            id="filter-verdict"
            className="filter-select"
            value={verdict}
            onChange={(e) => updateParams({ verdict: e.target.value, page: 0 })}
            data-testid="filter-verdict"
          >
            <option value="">All Verdicts</option>
            <option value="PASS">PASS</option>
            <option value="REJECT">REJECT</option>
            <option value="UNKNOWN">UNKNOWN</option>
          </select>
        </div>

        <div className="filter-group">
          <label htmlFor="filter-from" className="filter-label">
            From Date:
          </label>
          <input
            id="filter-from"
            type="datetime-local"
            className="filter-input"
            value={from}
            onChange={(e) => updateParams({ from: e.target.value, page: 0 })}
            data-testid="filter-from"
          />
        </div>

        <div className="filter-group">
          <label htmlFor="filter-to" className="filter-label">
            To Date:
          </label>
          <input
            id="filter-to"
            type="datetime-local"
            className="filter-input"
            value={to}
            onChange={(e) => updateParams({ to: e.target.value, page: 0 })}
            data-testid="filter-to"
          />
        </div>

        {(productType || verdict || from || to) && (
          <button
            type="button"
            className="btn-clear-filters"
            onClick={() =>
              setSearchParams(selectedLineId ? { lineId: String(selectedLineId) } : {})
            }
          >
            Reset Filters
          </button>
        )}
      </div>

      {/* Main Inspections Table */}
      <DataTable
        columns={columns}
        data={inspections}
        keyField="id"
        loading={loading}
        onRowClick={(row) => setSelectedInspection(row)}
        emptyState={
          <EmptyState
            title="No Inspections Found"
            description="No items recorded matching the current filter parameters."
          />
        }
        pagination={{
          page,
          totalPages,
          totalElements,
          onPageChange: (newPage) => updateParams({ page: newPage }),
        }}
      />

      {/* Inspection Detail Modal */}
      {selectedInspection && (
        <InspectionDetailModal
          inspection={selectedInspection}
          onClose={() => setSelectedInspection(null)}
        />
      )}
    </div>
  );
}
