# Local development launcher for the CarbonFlow Java backend (Spring Boot, :8080).
#
# The repo-root .env uses the RETIRED Node-era variable names (JWT_SECRET /
# REFRESH_TOKEN_SECRET). The Java backend reads CARBONFLOW_JWT_SECRET /
# CARBONFLOW_REFRESH_TOKEN_SECRET and FAILS CLOSED without them
# (JwtTokenProvider / AuthService throw at startup if missing or < 256 bits),
# which is why a bare `mvn spring-boot:run` dies immediately.
#
# Usage:  powershell -File scripts\run-backend.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $root 'backend-java')

# --- Required: database connection -------------------------------------------
$env:DB_HOST     = 'localhost'
$env:DB_PORT     = '5432'
$env:DB_NAME     = 'carbonflow_dev'
$env:DB_USER     = 'postgres'
$env:DB_PASSWORD = 'RLHc@2007'

# Local dev is plaintext; production MUST set require / verify-ca / verify-full.
$env:DB_SSLMODE  = 'prefer'

# --- Required: signing secrets (>= 32 bytes for HS256) ------------------------
# Development-only values. Generate real ones per environment:
#   [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))
$env:CARBONFLOW_JWT_SECRET         = 'carbonflow-jwt-super-secret-key-2026-sha256'
$env:CARBONFLOW_REFRESH_TOKEN_SECRET = 'carbonflow-refresh-super-secret-key-2026-sha512'

# --- Optional ----------------------------------------------------------------
# 'dev' profile supplies the localhost CORS origins (http://localhost:5173).
# The shipped default allow-list is empty (fail-closed), so the Vite dev server
# would be blocked in the browser without this.
$env:SPRING_PROFILES_ACTIVE = 'dev'

# Seeds demo identities on boot (SeedIds.DEMO_PASSWORD = 'Password123!').
# Off by default; enable only for local dev so there is something to log in with.
if ($env:CARBONFLOW_SEED_DEMO_DATA -eq 'true') {
    Write-Host 'Demo data seeding: ON' -ForegroundColor Yellow
} else {
    Write-Host 'Demo data seeding: OFF (no demo identities will exist)' -ForegroundColor Yellow
}

# Absolute, durable vault path for evidence file bytes (unencrypted at rest).
$env:CARBONFLOW_EVIDENCE_VAULT_DIR = Join-Path $root 'vault_storage'

Write-Host 'Starting CarbonFlow backend on http://localhost:8080' -ForegroundColor Green
Write-Host 'Health: http://localhost:8080/api/v1/health' -ForegroundColor Green

mvn spring-boot:run