/**
 * Derives the STOMP WebSocket broker URL from the HTTP(S) API base URL.
 * e.g. "http://localhost:8080" -> "ws://localhost:8080/ws"
 *      "https://qualiscan.internal" -> "wss://qualiscan.internal/ws"
 *
 * @param {string} [apiUrl]
 * @returns {string}
 */
export function getBrokerUrl(apiUrl = import.meta.env.VITE_API_URL || "http://localhost:8080") {
  try {
    const url = new URL(apiUrl);
    const wsProtocol = url.protocol === "https:" ? "wss:" : "ws:";
    const cleanPath = url.pathname.replace(/\/+$/, "");
    return `${wsProtocol}//${url.host}${cleanPath}/ws`;
  } catch {
    return "ws://localhost:8080/ws";
  }
}
