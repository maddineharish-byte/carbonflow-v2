# CarbonFlow — Execution & Operational Runbook

## 1. System Requirements & Local Execution
- **Node.js**: 20+ / 22+
- **Database**: PostgreSQL 15+ (or embedded persistent relational store for preview and local development without Docker dependency).
- **Environment**: Linux, macOS, or containerized Cloud Run.

---

## 2. Bootstrapping & Local Development

### 2.1 Dependency Installation
```bash
npm install
```

### 2.2 Environment Configuration
Copy `.env.example` to `.env`:
```env
JWT_SECRET=super-secret-jwt-key-carbonflow-enterprise-256
REFRESH_TOKEN_SECRET=super-secret-refresh-key-carbonflow-enterprise-512
STORAGE_DRIVER=local
```

### 2.3 Starting Application
```bash
npm run dev
```
The application will launch on `http://localhost:3000`, running the Express API gateway alongside the Vite frontend in unified full-stack middleware mode.

---

## 3. Database Migrations (Flyway)
Flyway migration scripts are located in `/db/migration/`:
- `V1__carbonflow_initial_schema.sql` — Creates UUID-keyed tables, indexes, and check constraints.
- `V2__seed_reference_data.sql` — Populates IPCC AR4/AR5/AR6 GWP sets, EPA/eGRID/DEFRA emission factors, and canonical RBAC roles.

When running against an external PostgreSQL database, provide connection values through environment variables; never place a password in the command or source:
```bash
flyway -url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" -user="${DB_USER}" -password="${DB_PASSWORD}" migrate
```

---

## 4. Production Build & Deployment
```bash
npm run build
npm start
```
This builds the client assets into `dist/` and bundles `server.ts` into a self-contained CommonJS artifact `dist/server.cjs` executed via Node.
