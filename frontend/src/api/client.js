/**
 * Typed API Error representation containing HTTP status, message, and server payload.
 */
export class ApiError extends Error {
  constructor(status, message, data = null) {
    super(message || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

const AUTH_STORAGE_KEY = "qualiscan_auth";

/**
 * Returns the currently active JWT token from sessionStorage, or null.
 */
export function getStoredToken() {
  try {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed?.token || null;
  } catch {
    return null;
  }
}

/**
 * Clears sessionStorage and forces navigation to /login upon 401 Unauthorized.
 */
export function handleUnauthorized() {
  try {
    sessionStorage.removeItem(AUTH_STORAGE_KEY);
  } catch {
    // Ignore storage errors in restricted contexts
  }
  // Only redirect if not already on the login page to avoid redirect loops
  if (typeof window !== "undefined" && window.location && !window.location.pathname?.startsWith("/login")) {
    try {
      window.location.href = "/login";
    } catch {
      // In JSDOM test environment, window.location.href assignment throws "Not implemented: navigation"
    }
  }
}

const BASE_URL = import.meta.env.VITE_API_URL || "http://localhost:8080";

/**
 * Lightweight fetch wrapper adhering strictly to vanilla fetch (no Axios).
 * Automatically injects the Bearer token, parses JSON, handles 401 session clearing,
 * and throws structured ApiErrors on non-2xx responses.
 *
 * @param {string} endpoint - Path starting with '/' (e.g. '/api/lines')
 * @param {RequestInit} [options={}] - Standard fetch options
 * @returns {Promise<any>} Parsed response data
 */
export async function apiClient(endpoint, options = {}) {
  const url = `${BASE_URL}${endpoint}`;
  const headers = new Headers(options.headers || {});

  // Inject Bearer token if not already explicitly provided
  const token = getStoredToken();
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  // Default Content-Type to application/json for requests with JSON body
  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(url, {
    ...options,
    headers,
  });

  // Handle 401 Unauthorized globally: clear session and redirect
  if (response.status === 401) {
    handleUnauthorized();
    let errorMsg = "Unauthorized: Session expired or invalid credentials";
    try {
      const errJson = await response.json();
      if (errJson?.message) errorMsg = errJson.message;
    } catch {
      // Fallback to default message
    }
    throw new ApiError(401, errorMsg);
  }

  // Handle other HTTP errors
  if (!response.ok) {
    let errorData = null;
    let message = `HTTP ${response.status} ${response.statusText}`;
    try {
      errorData = await response.json();
      if (errorData?.message) {
        message = errorData.message;
      } else if (errorData?.error) {
        message = errorData.error;
      }
    } catch {
      // No JSON body present
    }
    throw new ApiError(response.status, message, errorData);
  }

  // For 204 No Content
  if (response.status === 204) {
    return null;
  }

  // Return raw blob if caller requested blob (useful for image snapshots)
  if (options.asBlob) {
    return await response.blob();
  }

  return await response.json();
}
