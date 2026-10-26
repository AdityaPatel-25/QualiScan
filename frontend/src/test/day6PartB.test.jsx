import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter, Routes, Route, useSearchParams } from "react-router-dom";
import { AuthContext } from "../auth/AuthContext.jsx";
import { AlertsPage } from "../pages/AlertsPage.jsx";
import { SettingsPage } from "../pages/SettingsPage.jsx";
import { HistoryPage } from "../pages/HistoryPage.jsx";
import * as endpoints from "../api/endpoints.js";

describe("Day 6 Part B Comprehensive Tests", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  // =========================================================================
  // 1. ROLE GATING TESTS
  // =========================================================================
  describe("Role Gating (OPERATOR vs SUPERVISOR vs ADMIN)", () => {
    const mockAlerts = [
      {
        id: 101,
        lineId: 1,
        status: "OPEN",
        productType: "cap",
        type: "HIGH_DEFECT_RATE",
        defectRate: 0.15,
        threshold: 0.08,
        windowMinutes: 5,
        sampleSize: 30,
        message: "High defect rate on cap line",
        createdAt: "2026-10-26T10:00:00Z",
      },
    ];

    it("AlertsPage: OPERATOR sees read-only banner and no acknowledge/resolve buttons", async () => {
      vi.spyOn(endpoints, "getAlertsApi").mockResolvedValue({
        content: mockAlerts,
        totalElements: 1,
        totalPages: 1,
      });

      const operatorAuth = {
        user: { username: "oper_user", role: "OPERATOR" },
        isAuthenticated: true,
        token: "fake-oper-token",
      };

      render(
        <MemoryRouter>
          <AuthContext.Provider value={operatorAuth}>
            <AlertsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("readonly-notice")).toBeInTheDocument();
      });

      expect(screen.queryByTestId("btn-ack-101")).not.toBeInTheDocument();
      expect(screen.queryByTestId("btn-resolve-101")).not.toBeInTheDocument();
    });

    it("AlertsPage: SUPERVISOR sees acknowledge and resolve action buttons", async () => {
      vi.spyOn(endpoints, "getAlertsApi").mockResolvedValue({
        content: mockAlerts,
        totalElements: 1,
        totalPages: 1,
      });

      const supervisorAuth = {
        user: { username: "super_user", role: "SUPERVISOR" },
        isAuthenticated: true,
        token: "fake-super-token",
      };

      render(
        <MemoryRouter>
          <AuthContext.Provider value={supervisorAuth}>
            <AlertsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("btn-ack-101")).toBeInTheDocument();
        expect(screen.getByTestId("btn-resolve-101")).toBeInTheDocument();
      });

      expect(screen.queryByTestId("readonly-notice")).not.toBeInTheDocument();
    });

    it("SettingsPage: OPERATOR is restricted, SUPERVISOR can edit, but only ADMIN sees key rotation", async () => {
      vi.spyOn(endpoints, "getLineByIdApi").mockResolvedValue({
        id: 1,
        name: "Line Alpha",
        defectRateThreshold: 0.08,
        alertWindowMinutes: 5,
      });
      vi.spyOn(endpoints, "getProfilesApi").mockResolvedValue([
        { productType: "cap", rulesJson: '{"min_area": 100}' },
      ]);

      // 1. Test OPERATOR
      const { unmount: unmountOper } = render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "oper", role: "OPERATOR" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("readonly-settings-notice")).toBeInTheDocument();
      });
      expect(screen.queryByTestId("btn-save-settings")).not.toBeInTheDocument();
      expect(screen.queryByTestId("btn-save-profile")).not.toBeInTheDocument();
      expect(screen.queryByTestId("btn-rotate-key")).not.toBeInTheDocument();
      unmountOper();

      // 2. Test SUPERVISOR
      const { unmount: unmountSuper } = render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "super", role: "SUPERVISOR" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("btn-save-settings")).toBeInTheDocument();
        expect(screen.getByTestId("btn-save-profile")).toBeInTheDocument();
      });
      expect(screen.queryByTestId("btn-rotate-key")).not.toBeInTheDocument();
      unmountSuper();

      // 3. Test ADMIN
      render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "admin", role: "ADMIN" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("btn-rotate-key")).toBeInTheDocument();
      });
    });
  });

  // =========================================================================
  // 2. JSON VALIDATION OF THE PROFILE EDITOR
  // =========================================================================
  describe("Profile Editor JSON Validation", () => {
    it("disables Save and displays syntax error when JSON is invalid, enables when valid", async () => {
      vi.spyOn(endpoints, "getLineByIdApi").mockResolvedValue({
        id: 1,
        defectRateThreshold: 0.08,
        alertWindowMinutes: 5,
      });
      vi.spyOn(endpoints, "getProfilesApi").mockResolvedValue([
        { productType: "cap", rulesJson: '{"min_circularity": 0.85}' },
      ]);

      render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "super", role: "SUPERVISOR" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByText("✓ Valid JSON")).toBeInTheDocument();
      });

      const saveBtn = screen.getByTestId("btn-save-profile");
      expect(saveBtn).not.toBeDisabled();

      // Break the JSON syntax
      const textarea = screen.getByTestId("textarea-rules-json");
      fireEvent.change(textarea, { target: { value: '{"broken_json": ' } });

      expect(screen.getByText("⚠ Invalid JSON Syntax")).toBeInTheDocument();
      expect(screen.getByTestId("json-validation-error")).toBeInTheDocument();
      expect(saveBtn).toBeDisabled();

      // Restore valid JSON
      fireEvent.change(textarea, { target: { value: '{"fixed_key": 42}' } });
      expect(screen.getByText("✓ Valid JSON")).toBeInTheDocument();
      expect(screen.queryByTestId("json-validation-error")).not.toBeInTheDocument();
      expect(saveBtn).not.toBeDisabled();
    });
  });

  // =========================================================================
  // 3. PERCENTAGE TO FRACTION CONVERSION
  // =========================================================================
  describe("Percentage to Fraction Conversion", () => {
    it("converts user percentage (e.g. 12.5%) to fraction (0.125) on submission", async () => {
      vi.spyOn(endpoints, "getLineByIdApi").mockResolvedValue({
        id: 1,
        defectRateThreshold: 0.08,
        alertWindowMinutes: 5,
      });
      vi.spyOn(endpoints, "getProfilesApi").mockResolvedValue([]);
      const updateSettingsSpy = vi.spyOn(endpoints, "updateLineSettingsApi").mockResolvedValue({
        id: 1,
        defectRateThreshold: 0.125,
        alertWindowMinutes: 10,
      });

      render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "super", role: "SUPERVISOR" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("input-defect-threshold")).toHaveValue(8);
      });

      // Change percentage to 12.5% and window to 10
      fireEvent.change(screen.getByTestId("input-defect-threshold"), {
        target: { value: "12.5" },
      });
      fireEvent.change(screen.getByTestId("input-alert-window"), {
        target: { value: "10" },
      });

      fireEvent.click(screen.getByTestId("btn-save-settings"));

      await waitFor(() => {
        expect(updateSettingsSpy).toHaveBeenCalledWith(1, {
          defectRateThreshold: 0.125,
          alertWindowMinutes: 10,
        });
      });
    });
  });

  // =========================================================================
  // 4. FILTERS TO URL QUERY STRING LOGIC
  // =========================================================================
  describe("Filters to URL Query String Logic", () => {
    it("syncs filter changes into the URL query string and clears them on reset", async () => {
      vi.spyOn(endpoints, "getInspectionsApi").mockResolvedValue({
        content: [],
        totalPages: 0,
        totalElements: 0,
      });

      function URLInspector() {
        const [params] = useSearchParams();
        return <div data-testid="url-debug">{params.toString()}</div>;
      }

      render(
        <MemoryRouter initialEntries={["/history"]}>
          <Routes>
            <Route
              path="/history"
              element={
                <>
                  <HistoryPage selectedLineId={1} />
                  <URLInspector />
                </>
              }
            />
          </Routes>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("filter-product")).toBeInTheDocument();
      });

      // Select product 'cap'
      fireEvent.change(screen.getByTestId("filter-product"), {
        target: { value: "cap" },
      });
      await waitFor(() => {
        expect(screen.getByTestId("url-debug").textContent).toContain("productType=cap");
      });

      // Select verdict 'REJECT'
      fireEvent.change(screen.getByTestId("filter-verdict"), {
        target: { value: "REJECT" },
      });
      await waitFor(() => {
        expect(screen.getByTestId("url-debug").textContent).toContain("verdict=REJECT");
      });

      // Reset filters
      fireEvent.click(screen.getByText("Reset Filters"));
      await waitFor(() => {
        expect(screen.getByTestId("url-debug").textContent).not.toContain("productType=cap");
        expect(screen.getByTestId("url-debug").textContent).not.toContain("verdict=REJECT");
      });
    });
  });

  // =========================================================================
  // 5. KEY ROTATION ONE-TIME DISPLAY & NO PERSISTENCE
  // =========================================================================
  describe("Admin Key Rotation Dialog & Wiping", () => {
    it("displays newly generated key once, and purges it completely upon dismissal", async () => {
      vi.spyOn(endpoints, "getLineByIdApi").mockResolvedValue({
        id: 1,
        defectRateThreshold: 0.08,
        alertWindowMinutes: 5,
      });
      vi.spyOn(endpoints, "getProfilesApi").mockResolvedValue([]);
      vi.spyOn(endpoints, "rotateLineKeyApi").mockResolvedValue({
        apiKey: "SUPER-SECRET-NEW-KEY-ABC-999",
      });

      // Mock navigator.clipboard
      Object.assign(navigator, {
        clipboard: {
          writeText: vi.fn().mockResolvedValue(undefined),
        },
      });

      render(
        <MemoryRouter>
          <AuthContext.Provider
            value={{ user: { username: "admin", role: "ADMIN" }, isAuthenticated: true }}
          >
            <SettingsPage selectedLineId={1} />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      await waitFor(() => {
        expect(screen.getByTestId("btn-rotate-key")).toBeInTheDocument();
      });

      // 1. Click rotate key -> shows confirmation dialog
      fireEvent.click(screen.getByTestId("btn-rotate-key"));
      expect(screen.getByTestId("confirm-dialog")).toBeInTheDocument();
      expect(screen.getByText(/CRITICAL: Rotate Device Station API Key?/i)).toBeInTheDocument();

      // 2. Confirm rotation
      fireEvent.click(screen.getByTestId("dialog-confirm-button"));

      // 3. New key is presented once
      await waitFor(() => {
        expect(screen.getByTestId("revealed-key-modal")).toBeInTheDocument();
      });

      const keyInput = screen.getByTestId("revealed-key-value");
      expect(keyInput).toHaveValue("SUPER-SECRET-NEW-KEY-ABC-999");

      // 4. Test copy action
      fireEvent.click(screen.getByTestId("btn-copy-key"));
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith("SUPER-SECRET-NEW-KEY-ABC-999");
      expect(screen.getByText("Copied!")).toBeInTheDocument();

      // 5. Dismiss with "I Have Saved It Securely"
      fireEvent.click(screen.getByTestId("btn-key-saved-dismiss"));

      // 6. Modal is closed and key value is completely wiped from DOM
      expect(screen.queryByTestId("revealed-key-modal")).not.toBeInTheDocument();
      expect(screen.queryByText("SUPER-SECRET-NEW-KEY-ABC-999")).not.toBeInTheDocument();
    });
  });
});
