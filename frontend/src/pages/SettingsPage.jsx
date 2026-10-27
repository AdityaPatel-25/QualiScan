import React, { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useAuth } from "../auth/AuthContext.jsx";
import { hasRole } from "../auth/roles.js";
import {
  getLineByIdApi,
  updateLineSettingsApi,
  rotateLineKeyApi,
  getProfilesApi,
  updateProfileApi,
} from "../api/endpoints.js";
import { ConfirmDialog } from "../components/common/ConfirmDialog.jsx";
import { ErrorBanner } from "../components/common/ErrorBanner.jsx";
import { Spinner } from "../components/common/Spinner.jsx";

/**
 * Settings & CV Configuration Page.
 */
export function SettingsPage({ selectedLineId, lines = [] }) {
  const { user } = useAuth();
  const isSupervisor = hasRole(user?.role, "SUPERVISOR");
  const isAdmin = hasRole(user?.role, "ADMIN");

  // -------------------------------------------------------------
  // 1. LINE SETTINGS STATE
  // -------------------------------------------------------------
  const [lineData, setLineData] = useState(null);
  const [thresholdPct, setThresholdPct] = useState("8.0");
  const [alertWindow, setAlertWindow] = useState("5");
  const [savingSettings, setSavingSettings] = useState(false);
  const [settingsSuccess, setSettingsSuccess] = useState(null);
  const [settingsError, setSettingsError] = useState(null);

  // Load line settings
  const loadLineData = useCallback(async () => {
    if (!selectedLineId) return;
    try {
      const data = await getLineByIdApi(selectedLineId);
      setLineData(data);
      if (data?.defectRateThreshold != null) {
        setThresholdPct(String((data.defectRateThreshold * 100).toFixed(1)));
      }
      if (data?.alertWindowMinutes != null) {
        setAlertWindow(String(data.alertWindowMinutes));
      }
    } catch (err) {
      setSettingsError("Failed to fetch line configuration: " + err.message);
    }
  }, [selectedLineId]);

  useEffect(() => {
    loadLineData();
  }, [loadLineData]);

  // Handle Save Line Settings (Percentage converted to fraction)
  const handleSaveSettings = async (e) => {
    e.preventDefault();
    if (!isSupervisor) return;

    setSettingsError(null);
    setSettingsSuccess(null);

    const pctNum = parseFloat(thresholdPct);
    const winNum = parseInt(alertWindow, 10);

    if (isNaN(pctNum) || pctNum < 0 || pctNum > 100) {
      setSettingsError("Defect rate threshold must be between 0% and 100%.");
      return;
    }
    if (isNaN(winNum) || winNum < 1 || winNum > 1440) {
      setSettingsError("Alert rolling window must be between 1 and 1440 minutes.");
      return;
    }

    // Convert percentage to fraction (0.0 to 1.0)
    const fraction = Number((pctNum / 100).toFixed(4));

    setSavingSettings(true);
    try {
      await updateLineSettingsApi(selectedLineId, {
        defectRateThreshold: fraction,
        alertWindowMinutes: winNum,
      });
      setSettingsSuccess("Line telemetry thresholds updated successfully.");
      await loadLineData();
    } catch (err) {
      setSettingsError(err.message || "Failed to update line settings");
    } finally {
      setSavingSettings(false);
    }
  };

  // -------------------------------------------------------------
  // 2. PRODUCT PROFILES & RULES JSON STATE
  // -------------------------------------------------------------
  const [profiles, setProfiles] = useState([]);
  const [selectedProfileType, setSelectedProfileType] = useState("");
  const [rulesEditorText, setRulesEditorText] = useState("");
  const [loadingProfiles, setLoadingProfiles] = useState(false);
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileSuccess, setProfileSuccess] = useState(null);
  const [profileError, setProfileError] = useState(null);

  const selectedProfileTypeRef = useRef(selectedProfileType);
  selectedProfileTypeRef.current = selectedProfileType;

  // Load product profiles
  const loadProfiles = useCallback(async () => {
    setLoadingProfiles(true);
    try {
      const data = await getProfilesApi();
      const list = Array.isArray(data) ? data : [];
      setProfiles(list);
      if (list.length > 0 && !selectedProfileTypeRef.current) {
        setSelectedProfileType(list[0].productType);
        setRulesEditorText(formatJsonString(list[0].rulesJson));
      }
    } catch (err) {
      setProfileError("Failed to fetch product profiles: " + err.message);
    } finally {
      setLoadingProfiles(false);
    }
  }, []);

  useEffect(() => {
    loadProfiles();
  }, [loadProfiles]);

  // When profile selection changes
  const handleSelectProfile = (type) => {
    setSelectedProfileType(type);
    setProfileSuccess(null);
    setProfileError(null);
    const found = profiles.find((p) => p.productType === type);
    if (found) {
      setRulesEditorText(formatJsonString(found.rulesJson));
    }
  };

  function formatJsonString(raw) {
    if (!raw) return "{}";
    try {
      const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
      return JSON.stringify(parsed, null, 2);
    } catch {
      return String(raw);
    }
  }

  // Live JSON validation
  const jsonValidation = useMemo(() => {
    if (!rulesEditorText.trim()) {
      return { isValid: false, error: "Rules JSON cannot be empty." };
    }
    try {
      JSON.parse(rulesEditorText);
      return { isValid: true, error: null };
    } catch (e) {
      return { isValid: false, error: e.message };
    }
  }, [rulesEditorText]);

  // Handle Save Product Profile
  const handleSaveProfile = async (e) => {
    e.preventDefault();
    if (!isSupervisor) return;
    if (!jsonValidation.isValid) return;

    setSavingProfile(true);
    setProfileSuccess(null);
    setProfileError(null);
    try {
      // Minify or keep formatted
      const parsed = JSON.parse(rulesEditorText);
      await updateProfileApi(selectedProfileType, JSON.stringify(parsed));
      setProfileSuccess(`Profile rules for ${selectedProfileType.toUpperCase()} updated.`);
      await loadProfiles();
    } catch (err) {
      setProfileError(err.message || "Failed to update profile rules");
    } finally {
      setSavingProfile(false);
    }
  };

  // -------------------------------------------------------------
  // 3. ADMIN KEY ROTATION STATE
  // -------------------------------------------------------------
  const [showRotateConfirm, setShowRotateConfirm] = useState(false);
  const [rotatingKey, setRotatingKey] = useState(false);
  // Temporary one-time display key in modal (wiped immediately upon dismissal)
  const [revealedKey, setRevealedKey] = useState(null);
  const [copiedKey, setCopiedKey] = useState(false);
  const [keyRotateError, setKeyRotateError] = useState(null);

  // Execute rotate key
  const handleRotateKey = async () => {
    setRotatingKey(true);
    setKeyRotateError(null);
    setShowRotateConfirm(false);
    try {
      const res = await rotateLineKeyApi(selectedLineId);
      // Show once in modal
      setRevealedKey(res.apiKey);
      setCopiedKey(false);
    } catch (err) {
      setKeyRotateError("Key rotation failed: " + err.message);
    } finally {
      setRotatingKey(false);
    }
  };

  // Wipes key completely from component state
  const handleDismissRevealedKey = () => {
    setRevealedKey(null);
    setCopiedKey(false);
  };

  return (
    <div className="page-container settings-page-container">
      <div className="page-header-strip">
        <div>
          <h2 className="page-title">Line Settings & Optical Inspection Profiles</h2>
          <span className="page-subtitle text-muted">
            Configure defect tolerance limits, CV threshold rules, and station hardware keys
          </span>
        </div>
      </div>

      {!isSupervisor && (
        <div className="readonly-notice-banner" data-testid="readonly-settings-notice">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="16" x2="12" y2="12" />
            <line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span>
            Operator Mode: Settings & Profile Configurations are restricted to Supervisors and
            Administrators. Telemetry parameters are displayed in read-only mode.
          </span>
        </div>
      )}

      <div className="settings-grid">
        {/* ========================================================= */}
        {/* SECTION 1: LINE OPERATIONAL PARAMETERS                    */}
        {/* ========================================================= */}
        <div className="settings-card">
          <div className="card-header-styled">
            <h3 className="card-styled-title">Conveyor Line Thresholds</h3>
            <span className="card-styled-sub">
              {lineData?.name ? `${lineData.name} (#${selectedLineId})` : `Line #${selectedLineId}`}
            </span>
          </div>

          {settingsSuccess && <div className="banner-success">{settingsSuccess}</div>}
          {settingsError && <ErrorBanner message={settingsError} onDismiss={() => setSettingsError(null)} />}

          <form onSubmit={handleSaveSettings} className="settings-form">
            <div className="form-group">
              <label htmlFor="defect-threshold-input" className="form-label">
                Defect Rate Alert Threshold (%):
              </label>
              <div className="input-with-affix">
                <input
                  id="defect-threshold-input"
                  type="number"
                  step="0.1"
                  min="0"
                  max="100"
                  className="form-input font-mono"
                  value={thresholdPct}
                  onChange={(e) => setThresholdPct(e.target.value)}
                  disabled={!isSupervisor || savingSettings}
                  required
                  data-testid="input-defect-threshold"
                />
                <span className="affix">%</span>
              </div>
              <span className="input-hint">
                Triggers visual alarm banner when rolling defect rate exceeds this percentage (API
                receives {thresholdPct ? (parseFloat(thresholdPct) / 100).toFixed(4) : "0.0000"}).
              </span>
            </div>

            <div className="form-group">
              <label htmlFor="window-minutes-input" className="form-label">
                Alert Evaluation Window (Minutes):
              </label>
              <div className="input-with-affix">
                <input
                  id="window-minutes-input"
                  type="number"
                  min="1"
                  max="1440"
                  className="form-input font-mono"
                  value={alertWindow}
                  onChange={(e) => setAlertWindow(e.target.value)}
                  disabled={!isSupervisor || savingSettings}
                  required
                  data-testid="input-alert-window"
                />
                <span className="affix">min</span>
              </div>
              <span className="input-hint">
                Rolling duration evaluated for consecutive defects and pass yield metrics.
              </span>
            </div>

            {isSupervisor && (
              <div className="form-actions-right">
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={savingSettings}
                  data-testid="btn-save-settings"
                >
                  {savingSettings ? "Updating Line..." : "Save Line Settings"}
                </button>
              </div>
            )}
          </form>

          {/* ADMIN ONLY: Rotate Device Key */}
          {isAdmin && (
            <div className="admin-danger-zone">
              <div className="danger-header">
                <h4 className="danger-title">Device Station API Key</h4>
                <p className="danger-description">
                  Authentication token used by OpenCV camera nodes (cv_service) to post inspection
                  telemetry.
                </p>
              </div>

              {keyRotateError && <ErrorBanner message={keyRotateError} />}

              <button
                type="button"
                className="btn-danger-outline"
                onClick={() => setShowRotateConfirm(true)}
                disabled={rotatingKey}
                data-testid="btn-rotate-key"
              >
                {rotatingKey ? "Generating Key..." : "Rotate Camera Device Key"}
              </button>
            </div>
          )}
        </div>

        {/* ========================================================= */}
        {/* SECTION 2: PRODUCT PROFILES & RULES JSON                  */}
        {/* ========================================================= */}
        <div className="settings-card">
          <div className="card-header-styled">
            <h3 className="card-styled-title">Optical CV Rule Profiles</h3>
            <span className="card-styled-sub">
              Domain thresholds for contour area, circularity, aspect ratio, and HSV color masks
            </span>
          </div>

          {profileSuccess && <div className="banner-success">{profileSuccess}</div>}
          {profileError && <ErrorBanner message={profileError} onDismiss={() => setProfileError(null)} />}

          {loadingProfiles ? (
            <Spinner size="md" label="Loading profiles..." centered />
          ) : (
            <form onSubmit={handleSaveProfile} className="settings-form">
              {/* Profile Picker Tabs */}
              <div className="profile-selector-tabs">
                {profiles.map((p) => (
                  <button
                    key={p.productType}
                    type="button"
                    className={`profile-tab-btn ${
                      selectedProfileType === p.productType ? "active" : ""
                    }`}
                    onClick={() => handleSelectProfile(p.productType)}
                    data-testid={`profile-tab-${p.productType}`}
                  >
                    {p.productType.toUpperCase()}
                  </button>
                ))}
              </div>

              {/* JSON Editor Textarea */}
              <div className="form-group">
                <div className="label-with-badge">
                  <label htmlFor="rules-json-editor" className="form-label">
                    Rules JSON Specification:
                  </label>
                  {jsonValidation.isValid ? (
                    <span className="badge-json-valid">✓ Valid JSON</span>
                  ) : (
                    <span className="badge-json-invalid">⚠ Invalid JSON Syntax</span>
                  )}
                </div>

                <textarea
                  id="rules-json-editor"
                  rows="12"
                  className={`form-textarea font-mono ${
                    !jsonValidation.isValid ? "textarea-invalid" : ""
                  }`}
                  value={rulesEditorText}
                  onChange={(e) => setRulesEditorText(e.target.value)}
                  disabled={!isSupervisor || savingProfile}
                  data-testid="textarea-rules-json"
                />

                {!jsonValidation.isValid && (
                  <div className="text-danger text-xs font-mono" data-testid="json-validation-error">
                    {jsonValidation.error}
                  </div>
                )}
              </div>

              {/* Field Reference Explanation Guide */}
              <div className="rules-fields-guide">
                <span className="guide-title">Profile Fields Reference:</span>
                <ul className="guide-list">
                  <li>
                    <code>min_area / max_area</code>: Outer contour pixel surface area tolerance.
                  </li>
                  <li>
                    <code>min_circularity</code>: Perimeter compactness (4 * π * Area / Perimeter²) (1.0 for perfect circle).
                  </li>
                  <li>
                    <code>min_aspect_ratio / max_aspect_ratio</code>: Bounding box width to height ratio.
                  </li>
                  <li>
                    <code>hsv_min / hsv_max</code>: HSV color space boundary arrays for bake & tint validation.
                  </li>
                </ul>
              </div>

              {isSupervisor && (
                <div className="form-actions-right">
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={savingProfile || !jsonValidation.isValid}
                    data-testid="btn-save-profile"
                  >
                    {savingProfile ? "Saving Profile..." : "Save Product Profile"}
                  </button>
                </div>
              )}
            </form>
          )}
        </div>
      </div>

      {/* ADMIN Confirm Rotate Key Dialog */}
      <ConfirmDialog
        isOpen={showRotateConfirm}
        title="CRITICAL: Rotate Device Station API Key?"
        message={
          <div>
            <p>
              Rotating the device key will <strong>immediately revoke the existing key</strong>.
            </p>
            <p className="text-danger" style={{ marginTop: "0.5rem" }}>
              Active camera ingestion pipelines using the previous key will be rejected with HTTP 401
              until reconfigured with the newly generated key.
            </p>
          </div>
        }
        confirmLabel="Rotate & Invalidate Old Key"
        variant="danger"
        loading={rotatingKey}
        onConfirm={handleRotateKey}
        onCancel={() => setShowRotateConfirm(false)}
      />

      {/* ONE-TIME Display of Newly Generated API Key */}
      {revealedKey && (
        <div className="modal-overlay" role="presentation" onClick={handleDismissRevealedKey}>
          <div
            className="modal-dialog key-reveal-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="revealed-key-title"
            data-testid="revealed-key-modal"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="modal-header">
              <h3 id="revealed-key-title" className="modal-title text-success">
                New Camera Station Device Key
              </h3>
            </div>

            <div className="modal-body">
              <div className="key-warning-box">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
                <span>
                  Save this key securely now. For security purposes, this key will <strong>never be shown again</strong> and is never stored in browser memory.
                </span>
              </div>

              <div className="key-copy-container">
                <input
                  type="text"
                  readOnly
                  className="key-copy-input font-mono"
                  value={revealedKey}
                  data-testid="revealed-key-value"
                />
                <button
                  type="button"
                  className="btn-copy-key"
                  onClick={() => {
                    navigator.clipboard.writeText(revealedKey);
                    setCopiedKey(true);
                  }}
                  data-testid="btn-copy-key"
                >
                  {copiedKey ? "Copied!" : "Copy"}
                </button>
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                className="btn-primary"
                onClick={handleDismissRevealedKey}
                data-testid="btn-key-saved-dismiss"
              >
                I Have Saved It Securely
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
