import React from "react";
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  Cell,
  LineChart,
  Line,
  CartesianGrid,
} from "recharts";

const DEFECT_COLORS = [
  "#ef4444", // vibrant red
  "#f97316", // orange
  "#eab308", // yellow/amber
  "#a855f7", // purple
  "#06b6d4", // cyan
  "#ec4899", // pink
];

/**
 * Visual charts section:
 * 1. Defects distribution bar chart (defects by type in current window)
 * 2. Defect rate trend line chart across consecutive live stream updates
 *
 * @param {Object} props
 * @param {Array<{name: string, count: number}>} props.defectTypeData - Categorical defect counts
 * @param {Array<{time: string, rate: number}>} props.defectRateHistory - Temporal defect rate history
 * @param {number} [props.threshold=0.08] - Configured line threshold
 */
export function DefectsChart({
  defectTypeData = [],
  defectRateHistory = [],
  threshold = 0.08,
}) {
  const hasDefects = defectTypeData && defectTypeData.length > 0;
  const hasHistory = defectRateHistory && defectRateHistory.length > 0;

  return (
    <div className="charts-grid">
      {/* 1. Defects by Type Bar Chart */}
      <div className="chart-panel">
        <div className="chart-header">
          <div className="chart-title-group">
            <h3 className="chart-title">Defects Distribution</h3>
            <span className="chart-subtitle">Breakdown by flaw classification</span>
          </div>
          <span className="chart-badge">Active Window</span>
        </div>

        <div className="chart-canvas-wrapper">
          {!hasDefects ? (
            <div className="chart-empty-state">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <circle cx="12" cy="12" r="10" />
                <path d="m9 12 2 2 4-4" />
              </svg>
              <span>Zero defects recorded in current rolling window</span>
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={230}>
              <BarChart data={defectTypeData} margin={{ top: 12, right: 16, left: -15, bottom: 25 }}>
                <XAxis
                  dataKey="name"
                  stroke="#64748b"
                  fontSize={11}
                  tickLine={false}
                  interval={0}
                  angle={-20}
                  textAnchor="end"
                />
                <YAxis
                  stroke="#64748b"
                  fontSize={11}
                  tickLine={false}
                  allowDecimals={false}
                />
                <Tooltip
                  cursor={{ fill: "rgba(255, 255, 255, 0.04)" }}
                  contentStyle={{
                    backgroundColor: "#0f172a",
                    border: "1px solid rgba(255, 255, 255, 0.12)",
                    borderRadius: "8px",
                    color: "#f8fafc",
                    fontSize: "12px",
                  }}
                />
                <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                  {defectTypeData.map((entry, index) => (
                    <Cell
                      key={`cell-${index}`}
                      fill={DEFECT_COLORS[index % DEFECT_COLORS.length]}
                    />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      {/* 2. Defect Rate Trend Line Chart */}
      <div className="chart-panel">
        <div className="chart-header">
          <div className="chart-title-group">
            <h3 className="chart-title">Defect Rate Trend</h3>
            <span className="chart-subtitle">
              Live updates vs limit ({(threshold * 100).toFixed(1)}%)
            </span>
          </div>
          <span className="chart-badge">Live Trend</span>
        </div>

        <div className="chart-canvas-wrapper">
          {!hasHistory ? (
            <div className="chart-empty-state">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
              </svg>
              <span>Waiting for stream telemetry samples...</span>
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={230}>
              <LineChart data={defectRateHistory} margin={{ top: 12, right: 16, left: -15, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255, 255, 255, 0.05)" />
                <XAxis dataKey="time" stroke="#64748b" fontSize={11} tickLine={false} />
                <YAxis
                  stroke="#64748b"
                  fontSize={11}
                  tickLine={false}
                  domain={[0, (dataMax) => Math.max(threshold * 100 * 1.5, Math.ceil(dataMax + 2))]}
                  unit="%"
                />
                <Tooltip
                  contentStyle={{
                    backgroundColor: "#0f172a",
                    border: "1px solid rgba(255, 255, 255, 0.12)",
                    borderRadius: "8px",
                    color: "#f8fafc",
                    fontSize: "12px",
                  }}
                  formatter={(val) => [`${val}%`, "Defect Rate"]}
                />
                <Line
                  type="monotone"
                  dataKey="rate"
                  stroke="#f43f5e"
                  strokeWidth={2.5}
                  dot={{ r: 3, fill: "#f43f5e" }}
                  activeDot={{ r: 5 }}
                />
              </LineChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>
    </div>
  );
}
