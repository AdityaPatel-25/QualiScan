import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { AuthImage } from "../components/AuthImage.jsx";

describe("AuthImage component", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("renders the placeholder on 404 snapshot response", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: "Not Found",
    });

    render(
      <AuthProvider>
        <AuthImage snapshotId={999} alt="Missing Snapshot" />
      </AuthProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId("auth-image-placeholder")).toBeInTheDocument();
      expect(screen.getByText("No Snapshot Available")).toBeInTheDocument();
    });
  });

  it("renders loaded image on successful fetch and calls URL.revokeObjectURL on unmount", async () => {
    const mockBlob = new Blob(["fake-jpeg-binary"], { type: "image/jpeg" });
    const mockObjectUrl = "blob:http://localhost:5173/mock-uuid-123";

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => mockBlob,
    });

    const createSpy = vi.spyOn(URL, "createObjectURL").mockReturnValue(mockObjectUrl);
    const revokeSpy = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});

    const { unmount } = render(
      <AuthProvider>
        <AuthImage snapshotId={42} alt="Defective Cap" />
      </AuthProvider>
    );

    // Verify image was rendered with created object URL
    await waitFor(() => {
      const img = screen.getByTestId("auth-image-loaded");
      expect(img).toBeInTheDocument();
      expect(img).toHaveAttribute("src", mockObjectUrl);
      expect(createSpy).toHaveBeenCalledWith(mockBlob);
    });

    // Unmount and verify object URL was properly revoked to prevent memory leaks
    unmount();
    expect(revokeSpy).toHaveBeenCalledWith(mockObjectUrl);
  });
});
