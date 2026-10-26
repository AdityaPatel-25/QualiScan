import { useEffect, useState, useRef } from "react";
import { Client } from "@stomp/stompjs";
import { useAuth } from "../auth/AuthContext.jsx";
import { getAlertsApi } from "../api/endpoints.js";
import { getBrokerUrl } from "./wsUrl.js";
import { upsertAlert } from "./streamReducer.js";

/**
 * Realtime hook that manages alerts broadcast on `/topic/alerts`.
 * First fetches active OPEN alerts via REST, then maintains live updates over STOMP.
 *
 * @param {Object} [options]
 * @param {number|string} [options.lineId] - Optional line filter
 * @returns {{ alerts: Array<Object>, connected: boolean, connectionStatus: string }}
 */
export function useAlertStream(options = {}) {
  const { lineId } = options;
  const { token, isAuthenticated } = useAuth();
  const [alerts, setAlerts] = useState([]);
  const [connectionStatus, setConnectionStatus] = useState("offline");
  const stompClientRef = useRef(null);

  // 1. Initial REST fetch for active OPEN alerts
  useEffect(() => {
    let active = true;
    if (!isAuthenticated) return;

    getAlertsApi({ lineId, status: "OPEN", size: 50 })
      .then((data) => {
        if (active && data?.content) {
          setAlerts(data.content);
        }
      })
      .catch((err) => {
        console.warn("Initial alerts fetch error:", err.message);
      });

    return () => {
      active = false;
    };
  }, [lineId, isAuthenticated]);

  // 2. STOMP connection & subscription to /topic/alerts
  useEffect(() => {
    if (!isAuthenticated || !token) {
      setConnectionStatus("offline");
      return;
    }

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
        setConnectionStatus("connected");

        client.subscribe("/topic/alerts", (message) => {
          try {
            const alertPayload = JSON.parse(message.body);
            setAlerts((current) => upsertAlert(current, alertPayload));
          } catch (e) {
            console.error("Failed to parse alert message payload:", e);
          }
        });
      },
      onWebSocketClose: () => {
        setConnectionStatus("reconnecting");
      },
      onStompError: (frame) => {
        console.error("STOMP alert broker error:", frame.headers?.["message"]);
        setConnectionStatus("offline");
      },
    });

    stompClientRef.current = client;
    client.activate();

    return () => {
      if (stompClientRef.current) {
        stompClientRef.current.deactivate();
        stompClientRef.current = null;
      }
      setConnectionStatus("offline");
    };
  }, [token, isAuthenticated]);

  return {
    alerts,
    connected: connectionStatus === "connected",
    connectionStatus,
  };
}
