import React, { createContext, useContext, useState, useEffect, useCallback, useRef } from "react";
import { loginApi } from "../api/endpoints.js";

export const AuthContext = createContext(null);
const AUTH_STORAGE_KEY = "qualiscan_auth";

/**
 * Loads persisted auth session from sessionStorage.
 * Returns null if missing or expired.
 */
function getInitialSession() {
  try {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw);
    if (!session?.token || !session?.expiresAt) return null;

    // Check expiration immediately on load
    const expiryTime = new Date(session.expiresAt).getTime();
    if (Date.now() >= expiryTime) {
      sessionStorage.removeItem(AUTH_STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(getInitialSession);
  const [loading, setLoading] = useState(false);
  const logoutTimerRef = useRef(null);

  const logout = useCallback(() => {
    if (logoutTimerRef.current) {
      clearTimeout(logoutTimerRef.current);
      logoutTimerRef.current = null;
    }
    try {
      sessionStorage.removeItem(AUTH_STORAGE_KEY);
    } catch {
      // Ignore
    }
    setUser(null);
  }, []);

  // Sets up auto-logout timer whenever user session changes
  useEffect(() => {
    if (!user?.expiresAt) {
      if (logoutTimerRef.current) clearTimeout(logoutTimerRef.current);
      return;
    }

    const expiryTime = new Date(user.expiresAt).getTime();
    const remainingMs = expiryTime - Date.now();

    if (remainingMs <= 0) {
      logout();
      return;
    }

    logoutTimerRef.current = setTimeout(() => {
      logout();
    }, remainingMs);

    return () => {
      if (logoutTimerRef.current) {
        clearTimeout(logoutTimerRef.current);
        logoutTimerRef.current = null;
      }
    };
  }, [user, logout]);

  const login = async (username, password) => {
    setLoading(true);
    try {
      const data = await loginApi(username, password);
      // data: { token, role, expiresAt }
      const sessionUser = {
        username,
        role: data.role,
        expiresAt: data.expiresAt,
        token: data.token,
      };

      try {
        sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(sessionUser));
      } catch (err) {
        console.warn("Could not save auth session in sessionStorage", err);
      }

      setUser(sessionUser);
      return sessionUser;
    } finally {
      setLoading(false);
    }
  };

  const value = {
    user,
    token: user?.token || null,
    isAuthenticated: Boolean(user?.token),
    loading,
    login,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
