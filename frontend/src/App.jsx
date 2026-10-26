import React, { useState, useEffect } from "react";
import { BrowserRouter, Routes, Route, Navigate, Outlet } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext.jsx";
import { ProtectedRoute } from "./auth/ProtectedRoute.jsx";
import { TopNav } from "./components/TopNav.jsx";
import { LoginPage } from "./pages/LoginPage.jsx";
import { LiveDashboard } from "./pages/LiveDashboard.jsx";
import { HistoryPage } from "./pages/HistoryPage.jsx";
import { AlertsPage } from "./pages/AlertsPage.jsx";
import { ReportsPage } from "./pages/ReportsPage.jsx";
import { SettingsPage } from "./pages/SettingsPage.jsx";
import { getLinesApi } from "./api/endpoints.js";

import "./styles/index.css";
import "./styles/components.css";
import "./styles/dashboard.css";

/**
 * Main application shell wrapping authenticated pages with TopNav.
 */
function AppLayout({ lines, selectedLineId, onSelectLine }) {
  return (
    <>
      <TopNav
        lines={lines}
        selectedLineId={selectedLineId}
        onSelectLine={onSelectLine}
      />
      <main style={{ flex: 1, display: "flex", flexDirection: "column" }}>
        <Outlet />
      </main>
    </>
  );
}

function AppContent() {
  const { isAuthenticated } = useAuth();
  const [lines, setLines] = useState([]);
  const [selectedLineId, setSelectedLineId] = useState(() => {
    try {
      const saved = localStorage.getItem("qualiscan_active_line");
      return saved ? Number(saved) : null;
    } catch {
      return null;
    }
  });

  // Fetch production lines list when user is authenticated
  useEffect(() => {
    let active = true;
    if (!isAuthenticated) return;

    getLinesApi()
      .then((data) => {
        if (!active || !Array.isArray(data)) return;
        setLines(data);
        if (data.length > 0) {
          // If no selectedLineId or not in list, pick the first line
          setSelectedLineId((prev) => {
            const exists = prev != null && data.some((l) => Number(l.id) === Number(prev));
            const targetId = exists ? prev : data[0].id;
            try {
              localStorage.setItem("qualiscan_active_line", String(targetId));
            } catch {
              // Ignore
            }
            return targetId;
          });
        }
      })
      .catch((err) => {
        console.warn("Failed to load production lines:", err.message);
      });

    return () => {
      active = false;
    };
  }, [isAuthenticated]);

  const handleSelectLine = (lineId) => {
    setSelectedLineId(lineId);
    try {
      localStorage.setItem("qualiscan_active_line", String(lineId));
    } catch {
      // Ignore
    }
  };

  return (
    <Routes>
      {/* Public Login Route */}
      <Route path="/login" element={<LoginPage />} />

      {/* Protected Application Routes */}
      <Route
        element={
          <ProtectedRoute>
            <AppLayout
              lines={lines}
              selectedLineId={selectedLineId}
              onSelectLine={handleSelectLine}
            />
          </ProtectedRoute>
        }
      >
        <Route
          path="/live"
          element={
            <LiveDashboard
              lines={lines}
              selectedLineId={selectedLineId}
            />
          }
        />
        <Route
          path="/history"
          element={
            <HistoryPage
              selectedLineId={selectedLineId}
              lines={lines}
            />
          }
        />
        <Route
          path="/alerts"
          element={
            <AlertsPage
              selectedLineId={selectedLineId}
              lines={lines}
            />
          }
        />
        <Route
          path="/reports"
          element={
            <ReportsPage
              selectedLineId={selectedLineId}
              lines={lines}
            />
          }
        />
        <Route
          path="/settings"
          element={
            <SettingsPage
              selectedLineId={selectedLineId}
              lines={lines}
            />
          }
        />
      </Route>

      {/* Fallback & Root Redirect */}
      <Route path="/" element={<Navigate to="/live" replace />} />
      <Route path="*" element={<Navigate to="/live" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppContent />
      </AuthProvider>
    </BrowserRouter>
  );
}
