# QualiScan backend

Java 17, Spring Boot 3.3, Maven. The default profile stores H2 data in `./data/qualiscan-db` and snapshots in `./uploads/snapshots`.

Run tests with `./mvnw test` (Windows: `./mvnw.cmd test`). Start with `./mvnw spring-boot:run`. First startup seeds three demo accounts and one production line; the generated device key is printed once. Demo passwords: `Admin@123`, `Super@123`, `Oper@123`.

For PostgreSQL, set `SPRING_PROFILES_ACTIVE=postgres`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `JWT_SECRET` (at least 32 bytes). Override uploads with `UPLOAD_DIR`.

| Method | Path | Access |
|---|---|---|
| POST | `/api/auth/login` | Public |
| GET | `/api/lines`, `/api/lines/{id}` | Authenticated user |
| PUT | `/api/lines/{id}/settings` | ADMIN, SUPERVISOR |
| POST | `/api/lines/{id}/rotate-key` | ADMIN |
| GET, POST | `/api/admin/users` | ADMIN |
| PUT, DELETE | `/api/admin/users/{id}` | ADMIN |
| GET | `/api/profiles`, `/api/profiles/{productType}` | User or device key |
| PUT | `/api/profiles/{productType}` | ADMIN, SUPERVISOR |
| POST | `/api/batches`, `/api/batches/{id}/close` | ADMIN, SUPERVISOR |
| GET | `/api/batches` | Authenticated user |
| POST | `/api/inspections` | Device key (multipart `result`, optional JPEG `snapshot`) |
| GET | `/api/inspections`, `/api/snapshots/{inspectionId}` | Authenticated user |
| GET | `/api/stats/live`, `/api/stats/batch/{batchId}`, `/api/stats/hourly` | Authenticated user |
| GET | `/api/alerts` | Authenticated user |
| POST | `/api/alerts/{id}/acknowledge` | ADMIN, SUPERVISOR |
| POST | `/api/alerts/{id}/resolve` | ADMIN, SUPERVISOR |
| POST | `/api/reports/shift` | ADMIN, SUPERVISOR |
| GET | `/api/reports/shifts` | Authenticated user |

Inspection `result` is JSON containing `trackId`, `productType`, `confidence`, `verdict`, `failedChecks`, `area`, `circularity`, `aspectRatio`, and ISO-8601 `inspectedAt`.

## WebSocket (STOMP)

Plain WebSocket endpoint at `/ws` (allowed origins: `http://localhost:5173`, `http://localhost:3000`).
Application prefix: `/app`. In-memory broker prefix: `/topic`.

### Authentication
Handshake at `/ws` is permitted by Spring Security, but STOMP `CONNECT` frames must provide:
```text
Authorization: Bearer <jwt>
```
Validated with `JwtService`. Subscriptions require an authenticated user. Device API keys (`ROLE_DEVICE` or `X-API-Key`) are rejected.

A development test page is available at `/ws-test.html` (accessible without login; token is provided in-page).

### Topics & Message Shapes
- `/topic/lines/{lineId}/inspections`
  ```json
  {
    "id": 123,
    "trackId": "item-456",
    "productType": "cap",
    "verdict": "PASS",
    "failedChecks": [],
    "confidence": 0.99,
    "inspectedAt": "2026-10-24T10:00:00Z",
    "snapshotUrl": "/api/snapshots/123"
  }
  ```
- `/topic/lines/{lineId}/stats`
  Live quality counts and defect rate computed over the line's configured `alertWindowMinutes`.
- `/topic/alerts`
  Broadcast on alert creation (`OPEN`), acknowledgement (`ACKNOWLEDGED`), or resolution (`RESOLVED`).
  ```json
  {
    "id": 1,
    "lineId": 1,
    "productType": "cap",
    "type": "HIGH_DEFECT_RATE",
    "defectRate": 0.15,
    "threshold": 0.08,
    "windowMinutes": 5,
    "sampleSize": 30,
    "message": "High defect rate for cap on Line 1: 15.0% (threshold: 8.0%)",
    "status": "OPEN",
    "createdAt": "2026-10-24T10:00:00Z",
    "acknowledgedBy": null,
    "acknowledgedAt": null,
    "resolvedAt": null
  }
  ```

## Quality Alerts

Triggered automatically right after each inspection is saved:
1. Reuses `StatsService` rolling window (`alertWindowMinutes`).
2. Evaluates both the overall line and the specific `productType`.
3. Minimum sample guard: requires `pass + reject >= qualiscan.alerts.min-sample` (default 20) before evaluating.
4. Opens alert if `defectRate > line.defectRateThreshold` and no active (`OPEN` or `ACKNOWLEDGED`) alert exists for that line & productType.
5. Auto-resolves when `defectRate < line.defectRateThreshold * 0.8` (hysteresis prevents flapping).

## Shift Summaries

Aggregates shift performance over a time window:
- Tracks `total`, `pass`, `reject`, `unknown`, `defectRate`, `byProductJson`, and `defectsByTypeJson`.
- Idempotent: `POST /api/reports/shift` returns existing summary if already generated for `(lineId, from, to)`.
- Scheduled execution: cron job at 06:00, 14:00, 22:00 in `qualiscan.shifts.zone` (default `Asia/Kolkata`) automatically generates summaries for shifts A (06:00-14:00), B (14:00-22:00), and C (22:00-06:00).

## Demo Profile & Cloud Hosting

For public hosting (e.g. Render free-tier or public demonstrations):
- Set `SPRING_PROFILES_ACTIVE=demo`
- In-memory H2 database (`jdbc:h2:mem:qualiscan;DB_CLOSE_DELAY=-1`, schema created automatically).
- Server port dynamically bound via `server.port=${PORT:8080}`.
- Dynamic CORS origins configured via `CORS_ALLOWED_ORIGINS`.
- Public health probe: `GET /api/health` returning `{"status":"UP"}` without authentication.
- Single read-only operator account seeded from `DEMO_OPERATOR_PASSWORD` (minimum 12 characters, never creates admin or supervisor).
- Live simulator (`demo.simulator.enabled=true`) generating synthetic inspections every 3 to 5 seconds on a 10-minute oscillating sine wave (5% to 30% defect rate).
- In-memory rate limiting: limits login attempts to 10 per minute per IP, responding with `429 Too Many Requests` on subsequent attempts.
- Automated retention cleanup: maintains at most 5,000 inspection records and 100 snapshot files on disk.

### JVM Memory Tuning (512 MB Free-Tier Instances)

When deploying on resource-constrained containers (such as Render 512 MB free tier), configure JVM memory using `JAVA_TOOL_OPTIONS`:

```bash
JAVA_TOOL_OPTIONS="-Xmx300m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Xss512k"
```

- `-Xmx300m`: Constrains the maximum heap size to 300 MB, reserving ~212 MB for Metaspace, threads, native memory, and OS buffers.
- `-XX:+UseSerialGC`: Low-overhead single-threaded garbage collector optimized for single-core or memory-constrained containers, eliminating multi-threaded GC overhead.
- `-XX:MaxMetaspaceSize=128m`: Caps class metadata footprint to prevent out-of-memory container termination.

