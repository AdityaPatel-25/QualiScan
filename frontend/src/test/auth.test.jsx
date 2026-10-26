import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { ProtectedRoute } from "../auth/ProtectedRoute.jsx";
import { LoginPage } from "../pages/LoginPage.jsx";

describe("Auth & Route Protection", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it("shows an error message on 401 invalid credentials", async () => {
    // Mock 401 fetch response
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      statusText: "Unauthorized",
      json: async () => ({ message: "Invalid username or password" }),
    });

    render(
      <MemoryRouter initialEntries={["/login"]}>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/Operator \/ Username/i), {
      target: { value: "baduser" },
    });
    fireEvent.change(screen.getByLabelText(/Password/i), {
      target: { value: "wrongpass" },
    });
    fireEvent.click(screen.getByTestId("login-submit-button"));

    await waitFor(() => {
      expect(screen.getByTestId("login-error")).toBeInTheDocument();
      expect(screen.getByText(/Invalid username or password/i)).toBeInTheDocument();
    });
  });

  it("redirects to /live on successful login", async () => {
    const futureExpiry = new Date(Date.now() + 3600 * 1000).toISOString();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        token: "fake-jwt-token-xyz",
        role: "SUPERVISOR",
        expiresAt: futureExpiry,
      }),
    });

    render(
      <MemoryRouter initialEntries={["/login"]}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/live" element={<div>Live Dashboard Destination</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    );

    fireEvent.change(screen.getByLabelText(/Operator \/ Username/i), {
      target: { value: "supervisor" },
    });
    fireEvent.change(screen.getByLabelText(/Password/i), {
      target: { value: "Super@123" },
    });
    fireEvent.click(screen.getByTestId("login-submit-button"));

    await waitFor(() => {
      expect(screen.getByText("Live Dashboard Destination")).toBeInTheDocument();
    });
  });

  it("ProtectedRoute redirects to /login when no session exists", () => {
    render(
      <MemoryRouter initialEntries={["/live"]}>
        <AuthProvider>
          <Routes>
            <Route
              path="/live"
              element={
                <ProtectedRoute>
                  <div>Secret Protected Area</div>
                </ProtectedRoute>
              }
            />
            <Route path="/login" element={<div>Login Page Target</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    );

    expect(screen.queryByText("Secret Protected Area")).not.toBeInTheDocument();
    expect(screen.getByText("Login Page Target")).toBeInTheDocument();
  });
});
