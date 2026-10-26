import React from "react";

/**
 * Empty state placeholder when queries return zero results.
 *
 * @param {Object} props
 * @param {string} props.title
 * @param {string} [props.description]
 * @param {React.ReactNode} [props.icon]
 * @param {React.ReactNode} [props.action]
 */
export function EmptyState({
  title = "No Data Found",
  description = "No records match the selected filter criteria.",
  icon,
  action,
}) {
  return (
    <div className="empty-state-card" data-testid="common-empty-state">
      <div className="empty-icon-wrapper">
        {icon || (
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6">
            <rect x="2" y="3" width="20" height="14" rx="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
          </svg>
        )}
      </div>

      <h4 className="empty-title">{title}</h4>
      {description && <p className="empty-description">{description}</p>}
      {action && <div className="empty-action-wrapper">{action}</div>}
    </div>
  );
}
