import "@testing-library/jest-dom";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";

// Safe mock storage generator
function createMockStorage() {
  let store = {};
  return {
    getItem: vi.fn((key) => (key in store ? store[key] : null)),
    setItem: vi.fn((key, value) => {
      store[key] = String(value);
    }),
    removeItem: vi.fn((key) => {
      delete store[key];
    }),
    clear: vi.fn(() => {
      store = {};
    }),
    get length() {
      return Object.keys(store).length;
    },
    key: vi.fn((i) => Object.keys(store)[i] || null),
  };
}

// Ensure sessionStorage and localStorage have full reliable implementations in JSDOM / Node 25
const mockSessionStorage = createMockStorage();
const mockLocalStorage = createMockStorage();

Object.defineProperty(window, "sessionStorage", {
  value: mockSessionStorage,
  writable: true,
});
Object.defineProperty(global, "sessionStorage", {
  value: mockSessionStorage,
  writable: true,
});

Object.defineProperty(window, "localStorage", {
  value: mockLocalStorage,
  writable: true,
});
Object.defineProperty(global, "localStorage", {
  value: mockLocalStorage,
  writable: true,
});

// Polyfill window.URL.createObjectURL and revokeObjectURL in JSDOM
if (typeof window !== "undefined") {
  if (!window.URL.createObjectURL) {
    window.URL.createObjectURL = vi.fn(() => "blob:mock-url");
  }
  if (!window.URL.revokeObjectURL) {
    window.URL.revokeObjectURL = vi.fn();
  }

  // Safe window.location mock to prevent JSDOM "Not implemented: navigation" console errors
  delete window.location;
  window.location = {
    href: "http://localhost:5173/login",
    pathname: "/login",
    search: "",
    hash: "",
    assign: vi.fn(),
    replace: vi.fn(),
    reload: vi.fn(),
  };
}

// Automatically cleanup DOM and mocked stores after each test
afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
  window.localStorage.clear();
  vi.restoreAllMocks();
});
