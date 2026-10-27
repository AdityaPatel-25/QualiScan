import React, { useState, useEffect, useRef, useCallback } from "react";

/**
 * Handles cold-start detection for free-tier hosting (e.g. Render).
 * If the first API ping fails or takes > 4 seconds, displays a waking notification
 * with automatic retries every 5 seconds (up to 2 minutes), followed by a clear error and Retry button.
 */
export function ColdStartNotice({
  isDemoMode = String(import.meta.env.VITE_DEMO_MODE).toLowerCase() === "true",
  apiUrl = import.meta.env.VITE_API_URL || "http://localhost:8080",
  timeoutMs = 4000,
  retryIntervalMs = 5000,
  maxWaitMs = 120000,
  onAwake
}) {
  const [status, setStatus] = useState("idle"); // "idle" | "waking" | "awake" | "error"
  const [errorMessage, setErrorMessage] = useState(null);

  const attemptsRef = useRef(0);
  const activeTimerRef = useRef(null);
  const abortControllerRef = useRef(null);

  const maxAttempts = Math.max(1, Math.ceil(maxWaitMs / retryIntervalMs));

  const checkHealth = useCallback(async () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    const controller = new AbortController();
    abortControllerRef.current = controller;

    const timeoutId = setTimeout(() => {
      controller.abort();
    }, timeoutMs);

    try {
      const cleanBase = apiUrl.replace(/\/+$/, "");
      const res = await fetch(`${cleanBase}/api/health`, {
        signal: controller.signal
      });
      clearTimeout(timeoutId);
      return res.ok;
    } catch {
      clearTimeout(timeoutId);
      return false;
    }
  }, [apiUrl, timeoutMs]);

  const startWakingCycle = useCallback(() => {
    setStatus("waking");
    setErrorMessage(null);

    const poll = async () => {
      attemptsRef.current += 1;
      if (attemptsRef.current >= maxAttempts) {
        setStatus("error");
        setErrorMessage("The demo server did not respond within 2 minutes. Please verify network access or try again.");
        return;
      }

      const ok = await checkHealth();
      if (ok) {
        setStatus("awake");
        setErrorMessage(null);
        if (onAwake) onAwake();
      } else {
        activeTimerRef.current = setTimeout(poll, retryIntervalMs);
      }
    };

    activeTimerRef.current = setTimeout(poll, retryIntervalMs);
  }, [checkHealth, maxAttempts, retryIntervalMs, onAwake]);

  const handleManualRetry = () => {
    if (activeTimerRef.current) clearTimeout(activeTimerRef.current);
    attemptsRef.current = 0;
    setStatus("waking");
    setErrorMessage(null);

    checkHealth().then((ok) => {
      if (ok) {
        setStatus("awake");
        if (onAwake) onAwake();
      } else {
        startWakingCycle();
      }
    });
  };

  useEffect(() => {
    if (!isDemoMode) {
      return;
    }

    let cancelled = false;
    attemptsRef.current = 0;

    // Timer that triggers waking status if the initial check takes > timeoutMs
    const slowThresholdTimer = setTimeout(() => {
      if (!cancelled && status === "idle") {
        setStatus("waking");
        startWakingCycle();
      }
    }, timeoutMs);

    checkHealth()
      .then((ok) => {
        if (cancelled) return;
        clearTimeout(slowThresholdTimer);
        if (ok) {
          setStatus("awake");
          if (onAwake) onAwake();
        } else {
          startWakingCycle();
        }
      })
      .catch(() => {
        if (cancelled) return;
        clearTimeout(slowThresholdTimer);
        startWakingCycle();
      });

    return () => {
      cancelled = true;
      clearTimeout(slowThresholdTimer);
      if (activeTimerRef.current) clearTimeout(activeTimerRef.current);
      if (abortControllerRef.current) abortControllerRef.current.abort();
    };
  }, [isDemoMode, checkHealth, startWakingCycle, timeoutMs, onAwake]);

  if (!isDemoMode || status === "idle" || status === "awake") {
    return null;
  }

  return (
    <div
      className="cold-start-banner"
      role="status"
      aria-live="polite"
      data-testid="cold-start-notice"
    >
      <div className="cold-start-inner">
        {status === "waking" && (
          <>
            <span className="cold-start-spinner" aria-hidden="true" />
            <span className="cold-start-message">
              The demo server was asleep and is waking up (about a minute)...
            </span>
          </>
        )}

        {status === "error" && (
          <div className="cold-start-error-row">
            <span className="cold-start-error-icon" aria-hidden="true">⚠️</span>
            <span className="cold-start-error-text">
              {errorMessage || "Connection timed out after 2 minutes."}
            </span>
            <button
              type="button"
              className="cold-start-retry-button"
              data-testid="cold-start-retry-button"
              onClick={handleManualRetry}
            >
              Retry
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
