# Local development launcher for the CarbonFlow React frontend (Vite, :5173).
#
# Launched DETACHED via Start-Process. Running `npm run dev` as a child of the
# agent shell gets the whole process tree killed when that shell is torn down
# (observed twice), so both services are started fully detached instead.
#
# VITE_JAVA_API_BASE_URL is read at transform time by src/services/api.ts. If it
# is empty the frontend falls back to same-origin relative /api/v1/... paths,
# which the Vite dev server does NOT proxy (see vite.config.ts — there is no
# `server.proxy`), so every API call would 404 against :5173.
#
# Usage:  powershell -File scripts\run-frontend.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:VITE_JAVA_API_BASE_URL = 'http://localhost:8080'

Write-Host 'Starting CarbonFlow frontend on http://localhost:5173' -ForegroundColor Green
Write-Host "API base URL: $env:VITE_JAVA_API_BASE_URL" -ForegroundColor Green

npm run dev