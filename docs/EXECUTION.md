# CarbonFlow — Execution & Operational Runbook

> **Phase 10.5:** the Node/Express runtime is **DECOMMISSIONED**. There is no
> `npm start` and no API on port 3000. The Java backend is the API; the
> frontend is a static bundle.

## 1. System Requirements & Local Execution
- **Java**: 21 (runtime for the API)
- **Maven**: 3.9+ (build/test driver for the API)
- **Node.js**: 20+ — **build/dev/test tooling for the frontend only.** Node is
  not a production server; the frontend builds to static assets.
- **Database**: PostgreSQL 15+
- **Environment**: Linux, macOS, or containerized Cloud Run.

---

## 2. Bootstrapping & Local Development

### 2.1 Backend dependency install & build
```bash
cd backend-java
mvn clean verify        # compiles and runs the full test suite
```

### 2.2 Frontend dependency install
```bash
npm install
```

### 2.3 Environment Configuration
Copy `.env.example` to `.env` and fill in:
```env
CARBONFLOW_JWT_SECRET=<at least 32 random bytes>
CARBONFLOW_REFRESH_TOKEN_SECRET=<at least 32 random bytes>
DB_HOST=localhost
DB_PORT=5432
DB_NAME=carbonflow_dev
DB_USER=<user>
DB_PASSWORD=<password>
CARBONFLOW_CORS_ALLOWED_ORIGINS=http://localhost:5173
CARBONFLOW_EVIDENCE_VAULT_DIR=<absolute path to the private evidence vault>
```

The Java backend **refuses to start** if either secret is missing, blank, or
shorter than 32 bytes. This is deliberate (fail-closed).

The shipped `CARBONFLOW_CORS_ALLOWED_ORIGINS` default is empty, which trusts
**no** browser origin. Set it explicitly, or run with `SPRING_PROFILES_ACTIVE=dev`
to pick up the localhost development origins. `*` is refused at startup.

### 2.4 Starting the Application
Two processes, two terminals.

**Terminal 1 — API (Java):**
```bash
cd backend-java
mvn spring-boot:run
```
Listens on `http://localhost:8080`. Applies Flyway migrations at startup.

**Terminal 2 — frontend (Vite dev server):**
```bash
npm run dev
```
Listens on `http://localhost:5173` and proxies API calls to the Java backend
via `VITE_JAVA_API_BASE_URL`.

---

## 3. Database Migrations (Flyway)
Flyway migration scripts are located in `db/migration/`:
- `V1__carbonflow_initial_schema.sql` — Creates UUID-keyed tables, indexes, and check constraints.
- `V2__seed_reference_data.sql` — Populates IPCC AR4/AR5/AR6 GWP sets, EPA/eGRID/DEFRA emission factors, and canonical RBAC roles.
- `V3` … `V8` — Refresh-token persistence alignment, scope constraints, activity/evidence tenant integrity, calculation/emission integrity, audit status correction/rejection, organization lifecycle status.

`V1`–`V8` are **frozen**: never edited, and no migration is created for
convenience. The Java backend applies them automatically at startup. To migrate
an externally managed database ahead of time, use the Flyway CLI — never place
a password in the command or source:
```bash
flyway -url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" -user="${DB_USER}" -password="${DB_PASSWORD}" migrate
```

---

## 4. Production Build & Deployment

**API — build a runnable jar:**
```bash
cd backend-java
mvn clean package
java -jar target/carbonflow-backend-1.0.0-PRO.jar
```

**Frontend — build a static bundle:**
```bash
npm run build     # emits dist/
```

Deploy the two independently: run the jar as the API service, and serve
`dist/` as static assets from a CDN or web server. Point the browser at the API
using `VITE_JAVA_API_BASE_URL` at build time, or serve both behind one origin
so the frontend can use relative `/api/v1/...` paths.

Node is **not** part of the deployment. There is no `dist/server.cjs` and
nothing to `npm start`.

---

## 5. Verification Commands

```bash
# Backend
cd backend-java && mvn clean verify

# Frontend
npx tsc --noEmit
npm run lint
npm run test:frontend
npm run build
```

Health check once the API is running:

```bash
curl http://localhost:8080/api/v1/health
```
