# CarbonFlow — Enterprise Carbon Accounting Engine (Java Spring Boot 3 Edition)

This directory contains the standalone **Java (Spring Boot 3 + Java 17/21)** implementation of the **CarbonFlow GHG Accounting, Audit Ledger, and Dual-Reporting Engine**.

It matches 100% of the API contract, deterministic high-precision GHG calculations, multi-tenant isolation, and audit trail functionality of the CarbonFlow platform.

---

## Architecture & Technology Stack

- **Framework**: Spring Boot 3.3.3 (Jakarta EE)
- **Language**: Java 17+ (LTS) / Java 21 compatible
- **Security**: Spring Security 6 with stateless JWT authentication filter (`io.jsonwebtoken:jjwt`)
- **Calculation Precision**: Java `BigDecimal` with strict rounding (`RoundingMode.HALF_UP`) and exact physical unit normalization
- **Tamper-Evident Hashing**: Standard `MessageDigest` (SHA-256) cryptographic signatures for calculation traces and evidence records
- **Reporting**: Streaming RFC-4180 CSV export for GHG Protocol inventory ledgers

---

## Directory Structure

```
backend-java/
├── Dockerfile                                 # Multi-stage production container build
├── pom.xml                                    # Maven project definition
└── src/
    └── main/
        ├── java/com/carbonflow/
        │   ├── CarbonFlowApplication.java     # Spring Boot application entry point
        │   ├── config/
        │   │   ├── JwtAuthenticationFilter.java # Bearer & token parameter extractor
        │   │   ├── JwtTokenProvider.java        # HMAC-SHA256 token signer
        │   │   ├── SecurityConfig.java          # Spring Security 6 filter chain
        │   │   └── TenantContext.java           # ThreadLocal tenant boundary
        │   ├── controller/
        │   │   ├── ActivityDataController.java  # /api/v1/activity-data
        │   │   ├── AnalyticsController.java     # /api/v1/analytics/dashboard
        │   │   ├── AuditController.java         # /api/v1/audit-rooms
        │   │   ├── AuthController.java          # /api/v1/auth/login, /me
        │   │   ├── CalculationController.java   # /api/v1/calculations/run, /factors
        │   │   ├── EmissionLedgerController.java# /api/v1/emissions
        │   │   ├── EvidenceController.java      # /api/v1/evidence
        │   │   ├── FacilityController.java      # /api/v1/facilities
        │   │   ├── HealthController.java        # /api/health
        │   │   ├── ReportsController.java       # /api/v1/reports/export-csv
        │   │   └── TestSuiteController.java     # /api/v1/test-suite/run
        │   ├── dto/
        │   │   ├── ApiResponse.java             # Standard API envelope
        │   │   ├── AuthRequests.java
        │   │   ├── CalculationRequest.java
        │   │   └── DashboardSummaryDto.java
        │   ├── model/
        │   │   ├── ActivityData.java
        │   │   ├── AuditRoom.java
        │   │   ├── AuditTrailEvent.java
        │   │   ├── Calculation.java
        │   │   ├── EmissionFactor.java
        │   │   ├── EmissionRecord.java
        │   │   ├── EvidenceItem.java
        │   │   ├── Facility.java
        │   │   ├── Organization.java
        │   │   ├── ReportingPeriod.java
        │   │   ├── User.java
        │   │   └── enums/
        │   │       ├── AuditStatus.java
        │   │       ├── EmissionCategory.java
        │   │       ├── GHGScope.java
        │   │       ├── Role.java
        │   │       └── Scope2Method.java
        │   ├── repository/
        │   │   └── DataStore.java               # Thread-safe multi-tenant repository
        │   └── service/
        │       └── GhgCalculationEngine.java    # Deterministic BigDecimal GHG engine
        └── resources/
            └── application.properties         # Port, JWT secret, and CORS config
```

---

## Running the Java Backend

### Prerequisites
- Java 17 or higher (`openjdk-17-jdk` or Eclipse Temurin)
- Maven 3.8+

### Compile and Run Locally

```bash
cd backend-java
mvn clean package
mvn spring-boot:run
```

The server will start on port `8080` (or the port defined in `application.properties`).

### Running with Docker

```bash
cd backend-java
docker build -t carbonflow-backend-java .
docker run -p 8080:8080 carbonflow-backend-java
```

---

## Seed Accounts

| Email | Password | Organization | Role |
|---|---|---|---|
| `admin@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `SUPER_ADMIN` |
| `manager@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `SUSTAINABILITY_MANAGER` |
| `auditor@ey-assurance.com` | `Password123!` | Acme Global Manufacturing | `AUDITOR` |
| `admin@apexcorp.com` | `Password123!` | Apex CleanTech Logistics | `SUPER_ADMIN` |

---

## Automated Compliance Verification

Once booted, verify the compliance test suite with:

```bash
curl http://localhost:8080/api/v1/test-suite/run
```

All 7 automated tests verify tenant boundary isolation, decimal arithmetic precision, dual-reporting segregation, checklist guards, and cryptographic hashes.
