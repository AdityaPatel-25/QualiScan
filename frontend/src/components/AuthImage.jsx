import React, { useState, useEffect, useRef } from "react";
import { useAuth } from "../auth/AuthContext.jsx";

const BASE_URL = import.meta.env.VITE_API_URL || "http://localhost:8080";

/**
 * Image component that loads protected snapshot resources using the Bearer JWT token.
 * Renders a placeholder while loading or on 404 / network errors.
 * Ensures URL.revokeObjectURL is called on unmount and URL changes to prevent memory leaks.
 *
 * @param {Object} props
 * @param {number|string} [props.snapshotId] - ID of the inspection snapshot
 * @param {string} [props.url] - Optional direct URL path (e.g. '/api/snapshots/12')
 * @param {string} [props.alt] - Accessible alt text
 * @param {string} [props.className] - CSS class
 */
export function AuthImage({ snapshotId, url, alt = "Inspection Snapshot", className = "" }) {
  const { token } = useAuth();
  const [objectUrl, setObjectUrl] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const activeUrlRef = useRef(null);

  // Determine final request path
  const targetPath = url || (snapshotId != null ? `/api/snapshots/${snapshotId}` : null);

  useEffect(() => {
    // If no path specified, show fallback immediately
    if (!targetPath) {
      setError(true);
      return;
    }

    let isCancelled = false;
    setLoading(true);
    setError(false);

    const fullUrl = targetPath.startsWith("http") ? targetPath : `${BASE_URL}${targetPath}`;
    const headers = {};
    if (token) {
      headers["Authorization"] = `Bearer ${token}`;
    }

    fetch(fullUrl, { headers })
      .then((res) => {
        if (!res.ok) {
          throw new Error(`Failed to load image: HTTP ${res.status}`);
        }
        return res.blob();
      })
      .then((blob) => {
        if (isCancelled) return;
        const newObjectUrl = URL.createObjectURL(blob);
        activeUrlRef.current = newObjectUrl;
        setObjectUrl(newObjectUrl);
        setLoading(false);
      })
      .catch(() => {
        if (isCancelled) return;
        setError(true);
        setLoading(false);
      });

    return () => {
      isCancelled = true;
      if (activeUrlRef.current) {
        URL.revokeObjectURL(activeUrlRef.current);
        activeUrlRef.current = null;
      }
    };
  }, [targetPath, token]);

  if (loading) {
    return (
      <div className={`auth-image-placeholder loading ${className}`} data-testid="auth-image-loading">
        <div className="spinner-mini"></div>
        <span>Loading snapshot...</span>
      </div>
    );
  }

  if (error || !objectUrl) {
    return (
      <div className={`auth-image-placeholder error ${className}`} data-testid="auth-image-placeholder">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
          <circle cx="8.5" cy="8.5" r="1.5" />
          <polyline points="21 15 16 10 5 21" />
        </svg>
        <span>No Snapshot Available</span>
      </div>
    );
  }

  return (
    <img
      src={objectUrl}
      alt={alt}
      className={`auth-image ${className}`}
      data-testid="auth-image-loaded"
    />
  );
}
