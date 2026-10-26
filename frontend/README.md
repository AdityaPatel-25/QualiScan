# QualiScan Frontend — Real-Time Industrial QA Control Center

A React 18 single-page application built with Vite, vanilla CSS, and STOMP over WebSocket for real-time defect inspection monitoring, KPI telemetry, lot batch tracking, quality alert management, and optical CV rule configuration on manufacturing conveyor lines.

---

## 1. Quick Start & Setup

### Prerequisites
* **Node.js**: v18+ (tested on Node v25)
* **npm**: v9+ (tested on npm 11)
* Running Spring Boot backend (`http://localhost:8080`)

### Installation & Execution
```bash
# 1. Navigate to the frontend directory
cd frontend

# 2. Install dependencies
npm install

# 3. Start development server (Port 5173)
npm run dev

# 4. Run automated test suites (Vitest)
npm test

# 5. Build for production distribution
npm run build
```

---

## 2. Environment Variables

Create a `.env` file in the `frontend/` directory (copied from `.env.example`):

```bash
VITE_API_URL=http://localhost:8080
```

| Variable | Description | Default |
|---|---|---|
| `VITE_API_URL` | Base HTTP URL of the Spring Boot REST API. WebSocket STOMP endpoint is automatically derived (`http://` -> `ws://.../ws`). | `http://localhost:8080` |

---

## 3. Application Pages & Role-Based Access Control (RBAC)

The application provides 5 integrated pages with strict Role-Based Access Control (`ADMIN > SUPERVISOR > OPERATOR`):

| Page Route | Description | OPERATOR | SUPERVISOR | ADMIN |
|---|---|---|---|---|
| `/live` | Real-time conveyor feed, rolling KPI cards, per-product yield breakdown, Pareto defect distribution, and recent rejections gallery. | Read-Only | Read-Only | Read-Only |
| `/history` | Historical inspections table, URL query-string filter sync, item detail modal with geometric telemetry (area, circularity, aspect ratio), and client-side CSV export. | View & Export | View & Export | View & Export |
| `/alerts` | Quality incidents list with status filter (`OPEN`, `ACKNOWLEDGED`, `RESOLVED`), live updates from `/topic/alerts`, defect rate vs threshold metrics. | Read-Only Notice | Acknowledge & Resolve | Acknowledge & Resolve |
| `/reports` | Batch lot metrics, defects by category chart, 24-hour hourly throughput stacked bar chart, and shift summary audits. | View Reports | Start/Close Batch, Generate Shift | Start/Close Batch, Generate Shift |
| `/settings` | Line defect rate threshold (% to fraction), alert window, optical profile JSON editor with live syntax validation, and camera device key rotation. | Read-Only Notice | Edit Thresholds & Rules JSON | Full Access + Rotate Device Key |

---

## 4. Page Details & Workflow

### 1. Live Dashboard (`/live`)
* Connects via STOMP to `/topic/lines/{lineId}/inspections` and `/topic/lines/{lineId}/stats`.
* Displays rolling window KPIs: Total Inspected, Passed Conforming, Defective Rejects, and Defect Rate with breach alert highlights.
* Category breakdown cards for Bottle Caps (`cap`), Printed Cards (`card`), and Biscuits (`biscuit`).
* Recent Flagged Rejections gallery with Bearer-authenticated snapshot images.

### 2. Inspection History (`/history`)
* Queries `GET /api/inspections` with parameters `lineId`, `productType`, `verdict`, `from`, `to`, `page`, and `size`.
* Filters are synchronized bidirectionally with the URL query string (`useSearchParams`) enabling bookmarking and sharing of filtered views.
* Clicking any row opens an accessible detail modal showing the high-resolution snapshot thumbnail, physical measurements (`area`, `circularity`, `aspectRatio`), `trackId`, and `batchId`.
* **CSV Export**: Client-side export button fetches up to 1,000 filtered rows and generates an RFC 4180 compliant CSV download (`qualiscan_inspections_<timestamp>.csv`).

### 3. Quality Alerts (`/alerts`)
* Queries `GET /api/alerts` with status filtering (`ALL`, `OPEN`, `ACKNOWLEDGED`, `RESOLVED`).
* Maintains real-time synchronization over WebSocket (`/topic/alerts`) with instant upserting on alert creation, acknowledgement, and resolution.
* **SUPERVISOR / ADMIN**: Action buttons to **Acknowledge Alert** (`POST /api/alerts/{id}/acknowledge`) and **Resolve Alert** (`POST /api/alerts/{id}/resolve`) with confirmation dialogs.
* **OPERATOR**: Displays an informative read-only banner with disabled action controls.

### 4. Reports & Audits (`/reports`)
* **Batch Lot Performance**: Select lot batches (`GET /api/batches?lineId=`) to review totals, defect rate, defects-by-category Recharts bar chart, and per-product share. Supervisors can start a new lot batch (`POST /api/batches`) and close the active lot (`POST /api/batches/{id}/close`).
* **24-Hour Hourly Throughput**: Date picker loading `GET /api/stats/hourly?lineId={id}&date={YYYY-MM-DD}` into a stacked bar chart (Pass vs Reject volume).
* **Shift Audits**: Lists shift reports from `GET /api/reports/shifts`. Supervisors can generate custom shift summaries (`POST /api/reports/shift?lineId={id}&from={iso}&to={iso}`). If `partial: true` is returned, a warning is displayed noting that the period has not finished yet and the record is not permanently stored.

### 5. Settings & Optical CV Profiles (`/settings`)
* **Line Settings**: Supervisors can edit `defectRateThreshold` in percentage (0% to 100%), which is converted to a fractional value (`0.0 <= x <= 1.0`) before submitting via `PUT /api/lines/{id}/settings`.
* **Profile Rules Editor**: Edit contour and color rules for `cap`, `card`, and `biscuit`. Features live JSON syntax validation (Save button is disabled if syntax is invalid, with immediate syntax error diagnostics).
* **ADMIN Key Rotation**: Station device key rotation via `POST /api/lines/{id}/rotate-key` protected with a high-severity confirmation dialog. Once rotated, the new key is shown once in a copy box modal; dismissing the modal completely purges the key from state and memory.

---

## 5. Project Directory Structure

```text
frontend/
├── .env.example              # Environment variables template
├── index.html                # Application HTML with Inter and JetBrains Mono typography
├── package.json              # React 18, React Router v6, Recharts, @stomp/stompjs, Vitest
├── vite.config.js            # Vite build configuration (Port 5173)
├── src/
│   ├── api/
│   │   ├── client.js         # Fetch wrapper injecting JWT, parsing JSON, and handling 401 redirect
│   │   └── endpoints.js      # Typed API endpoints: auth, lines, stats, batches, alerts, profiles, shifts
│   ├── auth/
│   │   ├── AuthContext.jsx   # Authentication context, sessionStorage persistence & auto-logout timer
│   │   ├── ProtectedRoute.jsx# Route guard redirecting unauthenticated traffic to /login
│   │   └── roles.js          # RBAC hierarchy helper: ADMIN > SUPERVISOR > OPERATOR
│   ├── realtime/
│   │   ├── streamReducer.js  # Pure functions for newest-first capping (50 items) & alert upserts
│   │   ├── wsUrl.js          # Helper deriving ws:// or wss:// broker URL from VITE_API_URL
│   │   ├── useLineStream.js  # STOMP hook subscribing to inspections & stats with REST preloading
│   │   └── useAlertStream.js # STOMP hook subscribing to /topic/alerts
│   ├── components/
│   │   ├── common/
│   │   │   ├── Spinner.jsx       # Accessible loading indicator
│   │   │   ├── StatusBadge.jsx   # Universal status badge (verdicts, alert states, batches)
│   │   │   ├── ErrorBanner.jsx   # High-visibility error notification with retry handler
│   │   │   ├── EmptyState.jsx    # Empty placeholder card when zero records are returned
│   │   │   ├── ConfirmDialog.jsx # Accessible confirmation modal with keyboard Escape trap
│   │   │   └── DataTable.jsx     # Reusable responsive table with pagination and keyboard navigation
│   │   ├── TopNav.jsx        # Navigation bar, brand logo, line switcher, and user info
│   │   ├── ConnectionBadge.jsx # Live connection status indicator (connected, reconnecting, offline)
│   │   ├── KpiCard.jsx       # Metric cards with threshold breach visual warning
│   │   ├── ProductCard.jsx   # Per-product breakdown cards (cap, card, biscuit)
│   │   ├── DefectsChart.jsx  # Recharts defects distribution bar chart and live rate trend line
│   │   ├── RejectsGallery.jsx# Gallery displaying flagged defective items and triggered rules
│   │   ├── AuthImage.jsx     # Bearer-authenticated snapshot image component with URL revocation
│   │   └── AlertsBanner.jsx  # Prominent alert banner for active OPEN quality alerts
│   ├── pages/
│   │   ├── LoginPage.jsx     # Station sign-in page with demo credentials
│   │   ├── LiveDashboard.jsx # Real-time conveyor dashboard (/live)
│   │   ├── HistoryPage.jsx   # Historical inspections search & CSV export (/history)
│   │   ├── AlertsPage.jsx    # Quality alerts incident control center (/alerts)
│   │   ├── ReportsPage.jsx   # Lot batch analytics & shift reports (/reports)
│   │   └── SettingsPage.jsx  # Thresholds, CV profiles JSON editor & key rotation (/settings)
│   ├── styles/
│   │   ├── variables.css     # Dark industrial theme tokens (HSL colors, glassmorphism, fonts)
│   │   ├── index.css         # CSS reset, base typography, and custom scrollbars
│   │   ├── components.css    # Cards, buttons, tables, navigation, alerts, and modals
│   │   └── dashboard.css     # Responsive grid layouts and conveyor telemetry table
│   ├── test/
│   │   ├── setup.js          # Vitest environment setup with jest-dom
│   │   ├── auth.test.jsx     # Login 401 handling and ProtectedRoute tests
│   │   ├── authImage.test.jsx# AuthImage 404 fallback & URL.revokeObjectURL unmount test
│   │   ├── hasRole.test.js   # RBAC hierarchy tests (ADMIN > SUPERVISOR > OPERATOR)
│   │   ├── streamReducer.test.js # Pure reducer tests (newest-first, 50-item capping)
│   │   ├── dashboardComponents.test.jsx # Unit tests for TopNav, KpiCard, ProductCard, AlertsBanner
│   │   └── day6PartB.test.jsx# Role gating, JSON validation, % to fraction, URL sync, key rotation
│   ├── App.jsx               # Application routing and authenticated shell
│   └── main.jsx              # React DOM mounting entry point
```

---

## 6. Testing

Run all 30 automated unit and component tests:
```bash
npm test
```

Included test suites:
* Login 401 error rendering and redirect on success
* ProtectedRoute redirection when unauthenticated
* RBAC role hierarchy (`ADMIN > SUPERVISOR > OPERATOR`)
* `AuthImage` 404 placeholder rendering and `URL.revokeObjectURL` cleanup on unmount
* Stream reducer newest-first ordering, deduplication, and 50-item cap
* Dashboard components (`KpiCard`, `ProductCard`, `ConnectionBadge`, `AlertsBanner`, `TopNav`)
* Role gating on Alerts and Settings pages (`OPERATOR` vs `SUPERVISOR` vs `ADMIN`)
* Live JSON syntax validation on the product profile rules editor
* Defect rate percentage-to-fraction mathematical conversion (`12.5%` -> `0.125`)
* Historical inspections filter synchronization with URL query parameters
* Admin device key rotation one-time modal display and zero persistence on dismissal
