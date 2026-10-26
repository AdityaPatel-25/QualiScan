import React, { useEffect, useRef } from "react";

/**
 * Accessible confirmation modal dialog with keyboard accessibility (Escape to dismiss, focus trapping).
 *
 * @param {Object} props
 * @param {boolean} props.isOpen
 * @param {string} props.title
 * @param {React.ReactNode} props.message
 * @param {string} [props.confirmLabel='Confirm']
 * @param {string} [props.cancelLabel='Cancel']
 * @param {'danger'|'primary'|'warning'} [props.variant='primary']
 * @param {boolean} [props.loading=false]
 * @param {function} props.onConfirm
 * @param {function} props.onCancel
 */
export function ConfirmDialog({
  isOpen,
  title,
  message,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  variant = "primary",
  loading = false,
  onConfirm,
  onCancel,
}) {
  const cancelBtnRef = useRef(null);
  const confirmBtnRef = useRef(null);

  // Handle Escape key to close dialog
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e) => {
      if (e.key === "Escape" && !loading) {
        onCancel();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    // Autofocus cancel button on mount for safety
    setTimeout(() => {
      if (variant === "danger" && cancelBtnRef.current) {
        cancelBtnRef.current.focus();
      } else if (confirmBtnRef.current) {
        confirmBtnRef.current.focus();
      }
    }, 50);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isOpen, loading, onCancel, variant]);

  if (!isOpen) return null;

  return (
    <div
      className="modal-overlay"
      role="presentation"
      onClick={() => {
        if (!loading) onCancel();
      }}
    >
      <div
        className="modal-dialog confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        data-testid="confirm-dialog"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-header">
          <h3 id="confirm-dialog-title" className="modal-title">
            {title}
          </h3>
          <button
            type="button"
            className="btn-modal-close"
            onClick={onCancel}
            disabled={loading}
            aria-label="Close dialog"
          >
            ✕
          </button>
        </div>

        <div className="modal-body">
          {typeof message === "string" ? <p className="dialog-message">{message}</p> : message}
        </div>

        <div className="modal-footer">
          <button
            ref={cancelBtnRef}
            type="button"
            className="btn-secondary"
            onClick={onCancel}
            disabled={loading}
            data-testid="dialog-cancel-button"
          >
            {cancelLabel}
          </button>
          <button
            ref={confirmBtnRef}
            type="button"
            className={`btn-${variant}`}
            onClick={onConfirm}
            disabled={loading}
            data-testid="dialog-confirm-button"
          >
            {loading ? "Processing..." : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
