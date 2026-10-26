import { useEffect, useReducer, useRef, useCallback } from "react";
import { Client } from "@stomp/stompjs";
import { useAuth } from "../auth/AuthContext.jsx";
import { getLiveStatsApi, getInspectionsApi } from "../api/endpoints.js";
import { getBrokerUrl } from "./wsUrl.js";
import { useAlertStream } from "./useAlertStream.js";
import {
  streamReducer,
  initialStreamState,
} from "./streamReducer.js";

/**
 * High-performance realtime stream hook for an active production line.
 *
 * 1. Fetches initial data via REST (GET /api/stats/live and GET /api/inspections?size=20).
 * 2. Connects to the STOMP WebSocket broker with JWT auth header.
 * 3. Subscribes to /topic/lines/{lineId}/inspections and /topic/lines/{lineId}/stats.
 * 4. Combines with useAlertStream to expose alerts.
 * 5. Cleans up client/subscriptions on unmount or lineId change.
 *
 * @param {number|string|null} lineId - Selected production line ID
 * @returns {{
 *   connected: boolean,
 *   connectionStatus: 'connected' | 'reconnecting' | 'offline',
 *   lastInspections: Array<Object>,
 *   liveStats: Object|null,
 *   alerts: Array<Object>,
 *   error: string|null,
 *   reload: () => void
 * }}
 */
export function useLineStream(lineId) {
  const { token, isAuthenticated } = useAuth();
  const [state, dispatch] = useReducer(streamReducer, initialStreamState);
  const stompClientRef = useRef(null);

  // Hook into the shared alerts stream
  const { alerts, connectionStatus: alertConnStatus } = useAlertStream({ lineId });

  // Load initial REST state
  const loadInitialData = useCallback(async (activeLineId) => {
    if (!activeLineId) return;

    try {
      const [statsData, inspectionsData] = await Promise.all([
        getLiveStatsApi(activeLineId, 5).catch((err) => {
          console.warn("Failed to load initial live stats:", err.message);
          return null;
        }),
        getInspectionsApi({ lineId: activeLineId, size: 20 }).catch((err) => {
          console.warn("Failed to load initial inspections:", err.message);
          return null;
        }),
      ]);

      if (statsData) {
        dispatch({ type: "SET_STATS", payload: statsData });
      }

      if (inspectionsData?.content) {
        dispatch({ type: "INIT_INSPECTIONS", payload: inspectionsData.content });
      }
    } catch (err) {
      dispatch({ type: "SET_ERROR", payload: err.message });
    }
  }, []);

  // Connect STOMP client for the line
  useEffect(() => {
    if (!lineId || !isAuthenticated || !token) {
      dispatch({ type: "SET_STATUS", payload: "offline" });
      return;
    }

    let isSubscribed = true;

    // Load initial state first
    loadInitialData(lineId);

    const brokerURL = getBrokerUrl();
    const client = new Client({
      brokerURL,
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 3000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        if (!isSubscribed) return;
        dispatch({ type: "SET_STATUS", payload: "connected" });

        // 1. Subscribe to inspections topic
        const inspectionsTopic = `/topic/lines/${lineId}/inspections`;
        client.subscribe(inspectionsTopic, (message) => {
          try {
            const inspection = JSON.parse(message.body);
            dispatch({ type: "ADD_INSPECTION", payload: inspection });
          } catch (e) {
            console.error("Error parsing inspection frame:", e);
          }
        });

        // 2. Subscribe to stats topic
        const statsTopic = `/topic/lines/${lineId}/stats`;
        client.subscribe(statsTopic, (message) => {
          try {
            const stats = JSON.parse(message.body);
            dispatch({ type: "SET_STATS", payload: stats });
          } catch (e) {
            console.error("Error parsing stats frame:", e);
          }
        });
      },
      onWebSocketClose: () => {
        if (isSubscribed) {
          dispatch({ type: "SET_STATUS", payload: "reconnecting" });
        }
      },
      onStompError: (frame) => {
        console.error("STOMP Line Broker error:", frame.headers?.["message"]);
        if (isSubscribed) {
          dispatch({ type: "SET_STATUS", payload: "offline" });
        }
      },
    });

    stompClientRef.current = client;
    client.activate();

    return () => {
      isSubscribed = false;
      if (stompClientRef.current) {
        stompClientRef.current.deactivate();
        stompClientRef.current = null;
      }
      dispatch({ type: "SET_STATUS", payload: "offline" });
    };
  }, [lineId, token, isAuthenticated, loadInitialData]);

  // Overall connection status: prioritize line status, fallback to alert status
  const effectiveStatus = state.connectionStatus !== "offline"
    ? state.connectionStatus
    : alertConnStatus;

  return {
    connected: effectiveStatus === "connected",
    connectionStatus: effectiveStatus,
    lastInspections: state.lastInspections,
    liveStats: state.liveStats,
    alerts,
    error: state.error,
    reload: () => loadInitialData(lineId),
  };
}
