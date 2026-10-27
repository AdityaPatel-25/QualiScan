import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { DemoBanner } from "../components/DemoBanner.jsx";
import { ColdStartNotice } from "../components/ColdStartNotice.jsx";
import { LoginPage } from "../pages/LoginPage.jsx";
import { getBrokerUrl } from "../realtime/wsUrl.js";

describe("Demo Mode & Cold-Start Suite", () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    global.fetch = originalFetch;
    vi.useRealTimers();
  });

  describe("Demo Banner", () => {
    it("does not render when isDemo is false", () => {
      render(<DemoBanner isDemo={false} />);
      expect(screen.queryByTestId("demo-mode-banner")).toBeNull();
    });

    it("renders banner with repository link when isDemo is true", () => {
      const repoUrl = "https://github.com/AdityaPatel-25/QualiScan";
      render(<DemoBanner isDemo={true} repoUrl={repoUrl} />);

      const banner = screen.getByTestId("demo-mode-banner");
      expect(banner).toBeInTheDocument();
      expect(banner).toHaveTextContent("Demo mode: simulated live data. Real accuracy results are in the project README.");

      const link = screen.getByRole("link", { name: /project README/i });
      expect(link).toBeInTheDocument();
      expect(link).toHaveAttribute("href", repoUrl);
      expect(link).toHaveAttribute("target", "_blank");
    });
  });

  describe("WebSocket URL Derivation (wsUrl.js)", () => {
    it("derives ws:// for http:// URLs", () => {
      expect(getBrokerUrl("http://localhost:8080")).toBe("ws://localhost:8080/ws");
      expect(getBrokerUrl("http://127.0.0.1:8080/")).toBe("ws://127.0.0.1:8080/ws");
      expect(getBrokerUrl("http://api.internal:9000/subpath")).toBe("ws://api.internal:9000/subpath/ws");
    });

    it("derives wss:// for https:// URLs (hosted secure origins)", () => {
      expect(getBrokerUrl("https://qualiscan.onrender.com")).toBe("wss://qualiscan.onrender.com/ws");
      expect(getBrokerUrl("https://qualiscan.onrender.com/")).toBe("wss://qualiscan.onrender.com/ws");
      expect(getBrokerUrl("https://demo.qualiscan.app/backend")).toBe("wss://demo.qualiscan.app/backend/ws");
    });

    it("falls back gracefully on invalid URL strings", () => {
      expect(getBrokerUrl("invalid-not-a-url")).toBe("ws://localhost:8080/ws");
    });
  });

  describe("LoginPage Demo Account Box", () => {
    it("hides read-only demo card when isDemoMode is false", () => {
      render(
        <MemoryRouter>
          <AuthProvider>
            <LoginPage isDemoMode={false} />
          </AuthProvider>
        </MemoryRouter>
      );

      expect(screen.queryByTestId("demo-account-card")).toBeNull();
      expect(screen.queryByTestId("demo-fill-button")).toBeNull();
    });

    it("shows read-only demo card and fills operator credentials when clicked", () => {
      render(
        <MemoryRouter>
          <AuthProvider>
            <LoginPage
              isDemoMode={true}
              demoPassword="CustomDemoPassword123!"
            />
          </AuthProvider>
        </MemoryRouter>
      );

      expect(screen.getByTestId("demo-account-card")).toBeInTheDocument();
      expect(screen.getByText(/Public Demo Account \(Read-Only\)/i)).toBeInTheDocument();
      expect(screen.getByTestId("demo-operator-user")).toHaveTextContent("operator");
      expect(screen.getByTestId("demo-operator-pass")).toHaveTextContent("CustomDemoPassword123!");

      const fillButton = screen.getByTestId("demo-fill-button");
      expect(fillButton).toBeInTheDocument();

      fireEvent.click(fillButton);

      expect(screen.getByLabelText(/Operator \/ Username/i)).toHaveValue("operator");
      expect(screen.getByLabelText(/Password/i)).toHaveValue("CustomDemoPassword123!");
    });
  });

  describe("Cold-Start Handling & Automatic Retries", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("shows waking message when initial health check takes longer than 4s", async () => {
      global.fetch = vi.fn().mockImplementation(
        () => new Promise((resolve) => setTimeout(() => resolve({ ok: true, status: 200 }), 15000))
      );

      const { unmount } = render(
        <ColdStartNotice
          isDemoMode={true}
          apiUrl="http://localhost:8080"
          timeoutMs={4000}
          retryIntervalMs={5000}
          maxWaitMs={120000}
        />
      );

      expect(screen.queryByTestId("cold-start-notice")).toBeNull();

      act(() => {
        vi.advanceTimersByTime(4001);
      });

      expect(screen.getByTestId("cold-start-notice")).toBeInTheDocument();
      expect(
        screen.getByText("The demo server was asleep and is waking up (about a minute)...")
      ).toBeInTheDocument();

      unmount();
    });

    it("automatically retries every 5 seconds and resolves when backend wakes up", async () => {
      let callCount = 0;
      global.fetch = vi.fn().mockImplementation(() => {
        callCount++;
        if (callCount < 3) {
          return Promise.reject(new Error("Failed to fetch"));
        }
        return Promise.resolve({ ok: true, status: 200 });
      });

      const onAwake = vi.fn();

      const { unmount } = render(
        <ColdStartNotice
          isDemoMode={true}
          apiUrl="http://localhost:8080"
          timeoutMs={4000}
          retryIntervalMs={5000}
          maxWaitMs={120000}
          onAwake={onAwake}
        />
      );

      await act(async () => {
        await Promise.resolve();
      });

      expect(screen.getByTestId("cold-start-notice")).toBeInTheDocument();
      expect(
        screen.getByText("The demo server was asleep and is waking up (about a minute)...")
      ).toBeInTheDocument();

      // Retry 1 at 5s
      await act(async () => {
        vi.advanceTimersByTime(5000);
        await Promise.resolve();
      });

      expect(callCount).toBe(2);
      expect(screen.getByTestId("cold-start-notice")).toBeInTheDocument();

      // Retry 2 at 10s (succeeds)
      await act(async () => {
        vi.advanceTimersByTime(5000);
        await Promise.resolve();
      });

      expect(callCount).toBe(3);
      expect(onAwake).toHaveBeenCalled();
      expect(screen.queryByTestId("cold-start-notice")).toBeNull();

      unmount();
    });

    it("displays error with Retry button after max wait time is exceeded", async () => {
      global.fetch = vi.fn().mockRejectedValue(new Error("Server asleep"));

      const { unmount } = render(
        <ColdStartNotice
          isDemoMode={true}
          apiUrl="http://localhost:8080"
          timeoutMs={4000}
          retryIntervalMs={5000}
          maxWaitMs={10000}
        />
      );

      // Initial failure
      await act(async () => {
        await Promise.resolve();
      });

      expect(screen.getByTestId("cold-start-notice")).toBeInTheDocument();

      // Retry 1 (5s)
      await act(async () => {
        vi.advanceTimersByTime(5000);
        await Promise.resolve();
      });

      // Retry 2 (10s) -> reaches max attempts
      await act(async () => {
        vi.advanceTimersByTime(5000);
        await Promise.resolve();
      });

      expect(screen.getByText(/The demo server did not respond within 2 minutes/i)).toBeInTheDocument();
      const retryButton = screen.getByTestId("cold-start-retry-button");
      expect(retryButton).toBeInTheDocument();

      // Successful response on manual retry
      global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200 });

      await act(async () => {
        fireEvent.click(retryButton);
        await Promise.resolve();
      });

      expect(screen.queryByTestId("cold-start-notice")).toBeNull();

      unmount();
    });
  });
});
