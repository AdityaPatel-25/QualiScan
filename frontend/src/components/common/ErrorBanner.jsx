import React from "react";

/**
 * High-visibility error notification banner with optional retry handler.
 *
 * @param {Object} props
 * @param {string} props.message
 * @param {function} [props.onRetry]
 * @param {function} [props.onDismiss]
 */
export function ErrorBanner({ message, onRetry, onDismiss }) {
  if (!message) return null;

  return (
    <div className="error-banner" role="alert" data-testid="common-error-banner">
      <div className="error-icon">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
      </div>

      <div className="error-body">
        <span className="error-text">{message}</span>
      </div>

      <div className="error-actions">
        {onRetry && (
          <button type="button" onClick={onRetry} className="btn-retry">
            Retry
          </button>
        )}
        {onDismiss && (
          <button
            type="button"
            onClick={onDismiss}
            className="btn-dismiss"
            aria-label="Dismiss error notification"
          >
            ✕
          </button>
        )}
      </div>
    </div>
  );
}
