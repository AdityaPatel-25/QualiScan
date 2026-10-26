import { apiClient } from "./client.js";

/**
 * Authentication: POST /api/auth/login
 * @param {string} username
 * @param {string} password
 * @returns {Promise<{token: string, role: string, expiresAt: string}>}
 */
export async function loginApi(username, password) {
  return await apiClient("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

/**
 * Production Lines: GET /api/lines
 * @returns {Promise<Array<{id: number, name: string, activeBatchId: any, defectRateThreshold: number, alertWindowMinutes: number}>>}
 */
export async function getLinesApi() {
  return await apiClient("/api/lines");
}

/**
 * Production Line Details: GET /api/lines/{id}
 * @param {number|string} id
 */
export async function getLineByIdApi(id) {
  return await apiClient(`/api/lines/${id}`);
}

/**
 * Update Line Settings: PUT /api/lines/{id}/settings
 * @param {number|string} id
 * @param {{defectRateThreshold: number, alertWindowMinutes: number}} settings
 */
export async function updateLineSettingsApi(id, settings) {
  return await apiClient(`/api/lines/${id}/settings`, {
    method: "PUT",
    body: JSON.stringify(settings),
  });
}

/**
 * Rotate Production Line API Key: POST /api/lines/{id}/rotate-key (ADMIN only)
 * @param {number|string} id
 * @returns {Promise<{apiKey: string}>}
 */
export async function rotateLineKeyApi(id) {
  return await apiClient(`/api/lines/${id}/rotate-key`, {
    method: "POST",
  });
}

/**
 * Live Stats: GET /api/stats/live?lineId={id}&minutes={m}
 * @param {number|string} [lineId]
 * @param {number} [minutes=5]
 * @returns {Promise<{total: number, pass: number, reject: number, unknown: number, defectRate: number, perProductType: Array<any>}>}
 */
export async function getLiveStatsApi(lineId, minutes = 5) {
  const query = new URLSearchParams();
  if (lineId) query.set("lineId", lineId);
  if (minutes) query.set("minutes", minutes);
  const qs = query.toString();
  return await apiClient(`/api/stats/live${qs ? `?${qs}` : ""}`);
}

/**
 * Batch Statistics: GET /api/stats/batch/{batchId}
 * @param {number|string} batchId
 * @returns {Promise<{total: number, pass: number, reject: number, defectRate: number, defectsByType: Object, countsPerProductType: Object}>}
 */
export async function getBatchStatsApi(batchId) {
  return await apiClient(`/api/stats/batch/${batchId}`);
}

/**
 * Hourly Statistics: GET /api/stats/hourly?lineId={id}&date={YYYY-MM-DD}
 * @param {number|string} lineId
 * @param {string} date - Format YYYY-MM-DD
 * @returns {Promise<Array<{hour: number, pass: number, reject: number}>>}
 */
export async function getHourlyStatsApi(lineId, date) {
  const query = new URLSearchParams();
  query.set("lineId", lineId);
  query.set("date", date);
  return await apiClient(`/api/stats/hourly?${query.toString()}`);
}

/**
 * Inspections Search: GET /api/inspections
 * @param {Object} params
 * @param {number|string} [params.lineId]
 * @param {string} [params.productType]
 * @param {string} [params.verdict]
 * @param {string} [params.from] - UTC ISO timestamp
 * @param {string} [params.to] - UTC ISO timestamp
 * @param {number} [params.page=0]
 * @param {number} [params.size=20]
 * @returns {Promise<{content: Array<any>, totalElements: number, totalPages: number, number: number, size: number}>}
 */
export async function getInspectionsApi({
  lineId,
  productType,
  verdict,
  from,
  to,
  page = 0,
  size = 20,
} = {}) {
  const query = new URLSearchParams();
  if (lineId) query.set("lineId", lineId);
  if (productType) query.set("productType", productType);
  if (verdict) query.set("verdict", verdict);
  if (from) query.set("from", from);
  if (to) query.set("to", to);
  query.set("page", page);
  query.set("size", size);
  return await apiClient(`/api/inspections?${query.toString()}`);
}

/**
 * Quality Alerts: GET /api/alerts
 * @param {Object} params
 * @param {number|string} [params.lineId]
 * @param {string} [params.status]
 * @param {number} [params.page=0]
 * @param {number} [params.size=20]
 * @returns {Promise<{content: Array<any>, totalElements: number, totalPages: number, number: number}>}
 */
export async function getAlertsApi({ lineId, status, page = 0, size = 20 } = {}) {
  const query = new URLSearchParams();
  if (lineId) query.set("lineId", lineId);
  if (status) query.set("status", status);
  query.set("page", page);
  query.set("size", size);
  return await apiClient(`/api/alerts?${query.toString()}`);
}

/**
 * Acknowledge Alert: POST /api/alerts/{id}/acknowledge
 * @param {number|string} id
 */
export async function acknowledgeAlertApi(id) {
  return await apiClient(`/api/alerts/${id}/acknowledge`, {
    method: "POST",
  });
}

/**
 * Resolve Alert: POST /api/alerts/{id}/resolve
 * @param {number|string} id
 */
export async function resolveAlertApi(id) {
  return await apiClient(`/api/alerts/${id}/resolve`, {
    method: "POST",
  });
}

/**
 * Batches List: GET /api/batches?lineId={id}
 * @param {number|string} [lineId]
 * @returns {Promise<Array<{id: number, lineId: number, name: string, startedAt: string, endedAt: string, status: string}>>}
 */
export async function getBatchesApi(lineId) {
  const query = new URLSearchParams();
  if (lineId) query.set("lineId", lineId);
  const qs = query.toString();
  return await apiClient(`/api/batches${qs ? `?${qs}` : ""}`);
}

/**
 * Start New Batch: POST /api/batches
 * @param {{lineId: number|string, name: string}} data
 */
export async function startBatchApi(data) {
  return await apiClient("/api/batches", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

/**
 * Close Batch: POST /api/batches/{id}/close
 * @param {number|string} id
 */
export async function closeBatchApi(id) {
  return await apiClient(`/api/batches/${id}/close`, {
    method: "POST",
  });
}

/**
 * Product Profiles: GET /api/profiles
 * @returns {Promise<Array<{productType: string, rulesJson: string, updatedAt: string}>>}
 */
export async function getProfilesApi() {
  return await apiClient("/api/profiles");
}

/**
 * Update Product Profile: PUT /api/profiles/{productType}
 * @param {string} productType
 * @param {string} rulesJson
 */
export async function updateProfileApi(productType, rulesJson) {
  return await apiClient(`/api/profiles/${productType}`, {
    method: "PUT",
    body: JSON.stringify({ rulesJson }),
  });
}

/**
 * Shift Summaries: GET /api/reports/shifts
 * @param {Object} params
 * @param {number|string} [params.lineId]
 * @param {number} [params.page=0]
 * @param {number} [params.size=20]
 * @returns {Promise<{content: Array<any>, totalElements: number, totalPages: number}>}
 */
export async function getShiftReportsApi({ lineId, page = 0, size = 20 } = {}) {
  const query = new URLSearchParams();
  if (lineId) query.set("lineId", lineId);
  query.set("page", page);
  query.set("size", size);
  return await apiClient(`/api/reports/shifts?${query.toString()}`);
}

/**
 * Generate Shift Report: POST /api/reports/shift?lineId={id}&from={iso}&to={iso}
 * @param {number|string} lineId
 * @param {string} from - UTC ISO timestamp
 * @param {string} to - UTC ISO timestamp
 */
export async function generateShiftReportApi(lineId, from, to) {
  const query = new URLSearchParams();
  query.set("lineId", lineId);
  query.set("from", from);
  query.set("to", to);
  return await apiClient(`/api/reports/shift?${query.toString()}`, {
    method: "POST",
  });
}

/**
 * Snapshot Image: GET /api/snapshots/{id}
 * @param {number|string} inspectionId
 * @returns {Promise<Blob>}
 */
export async function getSnapshotBlobApi(inspectionId) {
  return await apiClient(`/api/snapshots/${inspectionId}`, {
    asBlob: true,
  });
}
