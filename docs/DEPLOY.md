# Deployment Guide: QualiScan Demo Environment

This document outlines the deployment configuration for running QualiScan in public demo mode using free-tier cloud hosting:
- **Backend**: Hosted on [Render](https://render.com/) (Web Service via Docker)
- **Frontend**: Hosted on [Vercel](https://vercel.com/) (Static SPA via Vite)

> **Important Security Architecture Note:**
> The demo operator account (`operator`) is **read-only and public by design**, allowing visitors to observe live synthetic conveyor inspections, telemetry charts, and quality alerts. Mutating actions (such as starting/closing batches, changing defect thresholds, rotating device hardware keys, and managing user accounts) are strictly forbidden (HTTP 403) for the operator role. **Every other credential and secret (including JWT signing secret, operator password, and database credentials) comes strictly from environment variables.** No secrets are stored in the codebase.

---

## 1. Backend Deployment on Render

Deploy the Spring Boot backend as a Docker Web Service on Render.

### Service Settings
- **Service Type**: Web Service
- **Environment**: Docker
- **Repository**: `https://github.com/AdityaPatel-25/QualiScan`
- **Root Directory**: `backend`
- **Region**: Closest to users (e.g., Oregon, Frankfurt, Singapore)
- **Instance Type**: Free (0.1 CPU, 512 MB RAM)
- **Health Check Path**: `/api/health`

### Environment Variables

| Variable Name | Example / Recommended Value | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `demo` | Activates in-memory H2, live conveyor simulator, rate limiter, and demo seeder. |
| `PORT` | `8080` | Render assigns this dynamically; backend binds via `server.port=${PORT:8080}`. |
| `JWT_SECRET` | `MySecretSigningKeyWithAtLeast32BytesOfEntropy!` | Secret key for HS256 JWT tokens. Must be at least 32 characters. |
| `DEMO_OPERATOR_PASSWORD` | `DemoOperatorPass2026!` | Password for the public read-only operator account (minimum 12 characters). |
| `CORS_ALLOWED_ORIGINS` | `https://qualiscan.vercel.app,http://localhost:5173` | Comma-separated list of permitted origins for REST and WebSocket STOMP. |
| `JAVA_TOOL_OPTIONS` | `-Xmx300m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k` | Tunes JVM memory to reliably run inside Render's 512 MB memory boundary. |

---

## 2. Frontend Deployment on Vercel

Deploy the React Vite single-page application on Vercel.

### Project Settings
- **Framework Preset**: Vite
- **Root Directory**: `frontend`
- **Build Command**: `npm run build`
- **Output Directory**: `dist`
- **Install Command**: `npm install`

### SPA Routing (`vercel.json`)
Client-side routes (such as `/live`, `/history`, `/alerts`, `/reports`, `/settings`, `/login`) are rewritten to `index.html` via `frontend/vercel.json`:
```json
{
  "rewrites": [
    {
      "source": "/(.*)",
      "destination": "/index.html"
    }
  ]
}
```

### Environment Variables

| Variable Name | Recommended Value | Description |
|---|---|---|
| `VITE_API_URL` | `https://qualiscan-backend.onrender.com` | Full HTTPS URL of the hosted Render backend. |
| `VITE_DEMO_MODE` | `true` | Enables the top demo banner, cold-start waking detection, and demo login card. |
| `VITE_REPO_URL` | `https://github.com/AdityaPatel-25/QualiScan` | GitHub repository link displayed in the demo banner. |
| `VITE_DEMO_OPERATOR_PASSWORD` | `VXY0Fcz09ng#!gUY` | Populated in the login card for one-click "Fill in" access. Must match `DEMO_OPERATOR_PASSWORD`. |

---

## 3. Cold-Start Behavior

Render free-tier web services sleep after 15 minutes of inactivity. When a visitor navigates to the Vercel frontend:
1. The frontend checks `GET /api/health`.
2. If the request times out (> 4 seconds) or fails, a waking banner appears:
   > *"The demo server was asleep and is waking up (about a minute)..."*
3. The frontend retries automatically every 5 seconds for up to 2 minutes.
4. Once the backend container boots and returns `{"status":"UP"}`, the banner dismisses automatically and live WebSocket streaming begins.
