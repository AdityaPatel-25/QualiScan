import { describe, it, expect } from "vitest";
import {
  prependInspection,
  normalizeInspection,
  upsertAlert,
  streamReducer,
  initialStreamState,
} from "../realtime/streamReducer.js";

describe("streamReducer pure functions & stream state", () => {
  it("normalizes inspection with string or array failedChecks", () => {
    const rawWithArray = {
      id: 1,
      trackId: "t1",
      productType: "cap",
      verdict: "REJECT",
      failedChecks: ["scratch", "dent"],
      confidence: 0.98,
    };
    const norm1 = normalizeInspection(rawWithArray);
    expect(norm1.failedChecks).toEqual(["scratch", "dent"]);
    expect(norm1.snapshotUrl).toBe("/api/snapshots/1");

    const rawWithString = {
      id: 2,
      trackId: "t2",
      productType: "card",
      verdict: "REJECT",
      failedChecks: "dent, skew",
      identificationConfidence: 0.95,
    };
    const norm2 = normalizeInspection(rawWithString);
    expect(norm2.failedChecks).toEqual(["dent", "skew"]);
    expect(norm2.confidence).toBe(0.95);
  });

  it("prepends new inspections newest-first and caps at 50", () => {
    let list = [];
    for (let i = 1; i <= 60; i++) {
      list = prependInspection(list, { id: i, trackId: `track-${i}`, verdict: "PASS" }, 50);
    }

    expect(list.length).toBe(50);
    // Newest element must be at index 0
    expect(list[0].id).toBe(60);
    // Oldest capped element at index 49
    expect(list[49].id).toBe(11);
  });

  it("deduplicates inspection with matching id on replay", () => {
    let list = [
      { id: 1, trackId: "t1", verdict: "PASS" },
      { id: 2, trackId: "t2", verdict: "PASS" },
    ];
    // Replay with updated verdict for id 1
    list = prependInspection(list, { id: 1, trackId: "t1", verdict: "REJECT" }, 50);
    expect(list.length).toBe(2);
    expect(list[0].id).toBe(1);
    expect(list[0].verdict).toBe("REJECT");
  });

  it("upserts alerts matching by ID", () => {
    const initialAlerts = [
      { id: 1, status: "OPEN", message: "Defect rate high" },
    ];

    // Update alert status
    const updated = upsertAlert(initialAlerts, { id: 1, status: "ACKNOWLEDGED", message: "Defect rate high" });
    expect(updated.length).toBe(1);
    expect(updated[0].status).toBe("ACKNOWLEDGED");

    // Add new alert
    const added = upsertAlert(updated, { id: 2, status: "OPEN", message: "Second line alert" });
    expect(added.length).toBe(2);
    expect(added[0].id).toBe(2);
  });

  it("streamReducer handles actions properly", () => {
    let state = streamReducer(initialStreamState, {
      type: "SET_STATUS",
      payload: "connected",
    });
    expect(state.connectionStatus).toBe("connected");
    expect(state.connected).toBe(true);

    state = streamReducer(state, {
      type: "ADD_INSPECTION",
      payload: { id: 101, trackId: "t-101", verdict: "PASS" },
    });
    expect(state.lastInspections.length).toBe(1);
    expect(state.lastInspections[0].id).toBe(101);

    state = streamReducer(state, {
      type: "SET_STATS",
      payload: { total: 100, pass: 95, reject: 5, defectRate: 0.05 },
    });
    expect(state.liveStats.total).toBe(100);
  });
});
