import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { KpiCard } from "../components/KpiCard.jsx";
import { ProductCard } from "../components/ProductCard.jsx";
import { ConnectionBadge } from "../components/ConnectionBadge.jsx";
import { AlertsBanner } from "../components/AlertsBanner.jsx";
import { TopNav } from "../components/TopNav.jsx";
import { AuthContext } from "../auth/AuthContext.jsx";

describe("Dashboard Components Unit Tests", () => {
  describe("KpiCard", () => {
    it("renders title, formatted value, subtitle, and normal styling", () => {
      render(
        <KpiCard
          title="TOTAL INSPECTED"
          value="1,240"
          subtitle="Rolling 5m evaluation"
          type="total"
        />
      );

      expect(screen.getByText("TOTAL INSPECTED")).toBeInTheDocument();
      expect(screen.getByText("1,240")).toBeInTheDocument();
      expect(screen.getByText("Rolling 5m evaluation")).toBeInTheDocument();

      const card = screen.getByTestId("kpi-card-total");
      expect(card).toHaveClass("kpi-total");
      expect(card).not.toHaveClass("threshold-breached");
    });

    it("applies threshold-breached class and displays thresholdInfo when exceeded", () => {
      render(
        <KpiCard
          title="DEFECT RATE"
          value="12.5%"
          subtitle="EXCEEDS TOLERANCE"
          type="rate"
          isExceeded={true}
          thresholdInfo="Limit: 8.0%"
        />
      );

      const card = screen.getByTestId("kpi-card-rate");
      expect(card).toHaveClass("threshold-breached");
      expect(screen.getByText("Limit: 8.0%")).toBeInTheDocument();
      expect(screen.getByText("Limit: 8.0%")).toHaveClass("text-danger");
    });
  });

  describe("ProductCard", () => {
    it("renders product metadata, icons, and computes yield correctly", () => {
      render(
        <ProductCard
          productType="cap"
          total={100}
          pass={92}
          reject={8}
          defectRate={0.08}
        />
      );

      expect(screen.getByText("CAP")).toBeInTheDocument();
      expect(screen.getByText("🥫")).toBeInTheDocument();
      expect(screen.getByText("Yield: 92.0%")).toBeInTheDocument();
      expect(screen.getByText("8.0%")).toBeInTheDocument();
      expect(screen.getByText("92")).toBeInTheDocument();
      expect(screen.getByText("8")).toBeInTheDocument();
    });

    it("displays appropriate icons for card and biscuit products", () => {
      const { rerender } = render(
        <ProductCard productType="card" total={50} pass={50} reject={0} defectRate={0} />
      );
      expect(screen.getByText("💳")).toBeInTheDocument();

      rerender(
        <ProductCard productType="biscuit" total={40} pass={35} reject={5} defectRate={0.125} />
      );
      expect(screen.getByText("🍪")).toBeInTheDocument();
    });
  });

  describe("ConnectionBadge", () => {
    it("renders correct labels and classes for connected, reconnecting, and offline states", () => {
      const { rerender } = render(<ConnectionBadge status="connected" />);
      let badge = screen.getByTestId("connection-badge");
      expect(badge).toHaveClass("status-connected");
      expect(screen.getByText("LIVE STREAM")).toBeInTheDocument();

      rerender(<ConnectionBadge status="reconnecting" />);
      badge = screen.getByTestId("connection-badge");
      expect(badge).toHaveClass("status-reconnecting");
      expect(screen.getByText("RECONNECTING")).toBeInTheDocument();

      rerender(<ConnectionBadge status="offline" />);
      badge = screen.getByTestId("connection-badge");
      expect(badge).toHaveClass("status-offline");
      expect(screen.getByText("STREAM OFFLINE")).toBeInTheDocument();
    });
  });

  describe("AlertsBanner", () => {
    const sampleAlerts = [
      {
        id: 10,
        lineId: 1,
        status: "OPEN",
        message: "High defect rate on Line #1",
        defectRate: 0.12,
        threshold: 0.08,
        productType: "cap",
        createdAt: "2026-10-26T10:00:00Z",
      },
      {
        id: 11,
        lineId: 2,
        status: "OPEN",
        message: "Line #2 alert",
        defectRate: 0.15,
        threshold: 0.08,
        productType: "card",
      },
      {
        id: 12,
        lineId: 1,
        status: "RESOLVED",
        message: "Resolved past issue",
        defectRate: 0.02,
        threshold: 0.08,
      },
    ];

    it("returns null when there are no matching active alerts", () => {
      const { container } = render(<AlertsBanner alerts={[]} lineId={1} />);
      expect(container.firstChild).toBeNull();
    });

    it("filters alerts by current lineId and only shows OPEN or ACKNOWLEDGED status", () => {
      render(<AlertsBanner alerts={sampleAlerts} lineId={1} />);

      const banner = screen.getByTestId("alerts-banner");
      expect(banner).toBeInTheDocument();
      expect(screen.getByText(/OPEN ALERT #10/i)).toBeInTheDocument();
      expect(screen.getByText("High defect rate on Line #1")).toBeInTheDocument();
      expect(screen.getByText(/12.0%/i)).toBeInTheDocument();

      // Line 2 alert and RESOLVED alert must not be shown
      expect(screen.queryByText(/Line #2 alert/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/Resolved past issue/i)).not.toBeInTheDocument();
    });
  });

  describe("TopNav", () => {
    const mockLines = [
      { id: 1, name: "Conveyor Line Alpha", activeBatchId: 101 },
      { id: 2, name: "Packaging Line Beta", activeBatchId: null },
    ];

    const mockAuthContextValue = {
      user: { username: "operator_alex", role: "SUPERVISOR" },
      isAuthenticated: true,
      logout: vi.fn(),
    };

    it("renders navigation items, line selector options, user details and handles line changes", () => {
      const handleSelectLine = vi.fn();

      render(
        <MemoryRouter>
          <AuthContext.Provider value={mockAuthContextValue}>
            <TopNav
              lines={mockLines}
              selectedLineId={1}
              onSelectLine={handleSelectLine}
            />
          </AuthContext.Provider>
        </MemoryRouter>
      );

      expect(screen.getByText("QualiScan")).toBeInTheDocument();
      expect(screen.getByText("Live")).toBeInTheDocument();
      expect(screen.getByText("History")).toBeInTheDocument();
      expect(screen.getByText("Alerts")).toBeInTheDocument();
      expect(screen.getByText("Reports")).toBeInTheDocument();
      expect(screen.getByText("Settings")).toBeInTheDocument();

      // Check user details
      expect(screen.getByText("operator_alex")).toBeInTheDocument();
      expect(screen.getByText("SUPERVISOR")).toBeInTheDocument();

      // Check line selection
      const select = screen.getByLabelText(/LINE:/i);
      expect(select.value).toBe("1");
      fireEvent.change(select, { target: { value: "2" } });
      expect(handleSelectLine).toHaveBeenCalledWith(2);

      // Check logout execution
      const exitButton = screen.getByTitle("Log out of QualiScan");
      fireEvent.click(exitButton);
      expect(mockAuthContextValue.logout).toHaveBeenCalled();
    });
  });
});
