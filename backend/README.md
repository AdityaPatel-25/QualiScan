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

Inspection `result` is JSON containing `trackId`, `productType`, `confidence`, `verdict`, `failedChecks`, `area`, `circularity`, `aspectRatio`, and ISO-8601 `inspectedAt`.
