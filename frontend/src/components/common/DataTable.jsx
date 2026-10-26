import React from "react";
import { Spinner } from "./Spinner.jsx";

/**
 * Reusable data table component with responsive wrapper, row click support,
 * and built-in pagination bar.
 *
 * @param {Object} props
 * @param {Array<{header: string, accessor?: string, cell?: function, width?: string, className?: string}>} props.columns
 * @param {Array<Object>} props.data
 * @param {string} [props.keyField='id']
 * @param {boolean} [props.loading=false]
 * @param {React.ReactNode} [props.emptyState]
 * @param {function} [props.onRowClick]
 * @param {Object} [props.pagination]
 * @param {number} props.pagination.page - 0-indexed
 * @param {number} props.pagination.totalPages
 * @param {number} props.pagination.totalElements
 * @param {function} props.pagination.onPageChange
 */
export function DataTable({
  columns = [],
  data = [],
  keyField = "id",
  loading = false,
  emptyState = null,
  onRowClick = null,
  pagination = null,
}) {
  return (
    <div className="data-table-container">
      <div className="table-responsive">
        <table className="app-data-table" data-testid="data-table">
          <thead>
            <tr>
              {columns.map((col, idx) => (
                <th
                  key={idx}
                  style={col.width ? { width: col.width } : undefined}
                  className={col.className || ""}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={columns.length} className="table-loading-cell">
                  <Spinner size="md" label="Loading records..." centered />
                </td>
              </tr>
            ) : data.length === 0 ? (
              <tr>
                <td colSpan={columns.length} className="table-empty-cell">
                  {emptyState || <div className="text-muted p-4">No records available</div>}
                </td>
              </tr>
            ) : (
              data.map((row, rowIdx) => {
                const rowKey = row[keyField] ?? rowIdx;
                const isClickable = typeof onRowClick === "function";

                return (
                  <tr
                    key={rowKey}
                    className={`table-row ${isClickable ? "row-clickable" : ""}`}
                    onClick={() => isClickable && onRowClick(row)}
                    tabIndex={isClickable ? 0 : undefined}
                    onKeyDown={(e) => {
                      if (isClickable && (e.key === "Enter" || e.key === " ")) {
                        e.preventDefault();
                        onRowClick(row);
                      }
                    }}
                  >
                    {columns.map((col, colIdx) => {
                      let cellContent = null;
                      if (typeof col.cell === "function") {
                        cellContent = col.cell(row);
                      } else if (col.accessor) {
                        cellContent = row[col.accessor];
                      }

                      return (
                        <td key={colIdx} className={col.className || ""}>
                          {cellContent}
                        </td>
                      );
                    })}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination Bar */}
      {pagination && pagination.totalPages > 1 && (
        <div className="table-pagination-bar" data-testid="table-pagination">
          <div className="pagination-info">
            Showing Page <span className="font-mono">{pagination.page + 1}</span> of{" "}
            <span className="font-mono">{pagination.totalPages}</span>
            {pagination.totalElements != null && (
              <span className="text-muted"> ({pagination.totalElements} total entries)</span>
            )}
          </div>

          <div className="pagination-controls">
            <button
              type="button"
              className="btn-pagination"
              disabled={pagination.page <= 0 || loading}
              onClick={() => pagination.onPageChange(pagination.page - 1)}
              data-testid="pagination-prev"
            >
              Previous
            </button>
            <span className="pagination-page-indicator">
              {pagination.page + 1} / {pagination.totalPages}
            </span>
            <button
              type="button"
              className="btn-pagination"
              disabled={pagination.page >= pagination.totalPages - 1 || loading}
              onClick={() => pagination.onPageChange(pagination.page + 1)}
              data-testid="pagination-next"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
