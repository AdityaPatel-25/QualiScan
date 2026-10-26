/**
 * Normalizes inspection object fields to ensure consistent shape regardless
 * of whether the data arrived via REST search or WebSocket STOMP broadcast.
 *
 * @param {Object} raw
 * @returns {Object}
 */
export function normalizeInspection(raw) {
  if (!raw) return null;

  // Handle failedChecks: can be Array<string> (from WS) or comma-separated string (from REST)
  let failedChecks = [];
  if (Array.isArray(raw.failedChecks)) {
    failedChecks = raw.failedChecks;
  } else if (typeof raw.failedChecks === "string" && raw.failedChecks.trim().length > 0) {
    failedChecks = raw.failedChecks.split(",").map((s) => s.trim()).filter(Boolean);
  }

  const id = raw.id;
  const snapshotUrl = raw.snapshotUrl || (id != null ? `/api/snapshots/${id}` : null);

  return {
    id: raw.id,
    lineId: raw.lineId,
    trackId: raw.trackId,
    productType: raw.productType,
    verdict: raw.verdict,
    failedChecks,
    confidence: raw.confidence ?? raw.identificationConfidence ?? 0,
    inspectedAt: raw.inspectedAt,
    area: raw.area,
    circularity: raw.circularity,
    aspectRatio: raw.aspectRatio,
    snapshotUrl,
  };
}

/**
 * Pure helper to prepend a new inspection item, deduplicate by id, and cap at maxItems.
 *
 * @param {Array<Object>} currentList
 * @param {Object} newItem
 * @param {number} [maxItems=50]
 * @returns {Array<Object>} New array with item prepended and length <= maxItems
 */
export function prependInspection(currentList = [], newItem, maxItems = 50) {
  if (!newItem) return currentList;
  const normalized = normalizeInspection(newItem);

  // Filter out any existing item with the same ID to prevent duplication during WS replays
  const filtered = currentList.filter((item) => item.id !== normalized.id);
  const updated = [normalized, ...filtered];

  return updated.slice(0, maxItems);
}

/**
 * Pure helper to update or insert an alert in the alerts list.
 *
 * @param {Array<Object>} alerts
 * @param {Object} alertItem
 * @returns {Array<Object>}
 */
export function upsertAlert(alerts = [], alertItem) {
  if (!alertItem || alertItem.id == null) return alerts;
  const index = alerts.findIndex((a) => a.id === alertItem.id);

  if (index >= 0) {
    const next = [...alerts];
    next[index] = { ...next[index], ...alertItem };
    return next;
  }

  return [alertItem, ...alerts];
}

export const initialStreamState = {
  connectionStatus: "offline", // 'connected' | 'reconnecting' | 'offline'
  connected: false,
  lastInspections: [],
  liveStats: null,
  alerts: [],
  error: null,
};

/**
 * Reducer for managing real-time conveyor and alert state.
 */
export function streamReducer(state, action) {
  switch (action.type) {
    case "SET_STATUS":
      return {
        ...state,
        connectionStatus: action.payload,
        connected: action.payload === "connected",
      };

    case "INIT_INSPECTIONS": {
      const normalized = (action.payload || []).map(normalizeInspection);
      return {
        ...state,
        lastInspections: normalized.slice(0, 50),
      };
    }

    case "ADD_INSPECTION": {
      return {
        ...state,
        lastInspections: prependInspection(state.lastInspections, action.payload, 50),
      };
    }

    case "SET_STATS":
      return {
        ...state,
        liveStats: action.payload,
      };

    case "INIT_ALERTS":
      return {
        ...state,
        alerts: action.payload || [],
      };

    case "UPSERT_ALERT":
      return {
        ...state,
        alerts: upsertAlert(state.alerts, action.payload),
      };

    case "SET_ERROR":
      return {
        ...state,
        error: action.payload,
      };

    case "RESET":
      return {
        ...initialStreamState,
      };

    default:
      return state;
  }
}
