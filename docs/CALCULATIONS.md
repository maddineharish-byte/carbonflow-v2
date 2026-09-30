# CarbonFlow — GHG Calculation Engine & Mathematical Specification

## 1. Mathematical Architecture & Precision Rules

Carbon accounting requires deterministic arithmetic identical to financial ledgers. Standard IEEE-754 floating-point operations (`0.1 + 0.2 = 0.30000000000000004`) lead to cumulative discrepancies that fail external audit verification.

### Precision Standards
- **Implementation**: Java `BigDecimal` (sole implementation).
- **Phase 10.5 note**: the Spring Boot backend's `GhgCalculationEngine` is the only implementation of this specification. The TypeScript `Decimal.js` parity oracle (`server/calc.ts`) was decommissioned with the Node backend; it remains in Git history at `4cc8f30` and in the Phase 10 evidence documents.
- **Internal Computation Scale**: 28 decimal places (`MathContext`), reported snapshots at 8 decimal places for normalized quantities.
- **Reporting Scale**: 4 decimal places for metric tonnes CO2e (`tCO2e`), 2 decimal places for kilogram CO2e (`kgCO2e`).
- **Rounding Mode**: `HALF_UP` (standard accounting rounding); ledger totals accumulate **unrounded** products and round only at presentation.

---

## 2. Core Calculation Pipeline

```
┌─────────────────┐
│  Activity Data  │ (e.g. 5,000 Gallons Diesel, Stationary Generator)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Factor Lookup   │ Selects the active factor version for the activity type,
│ & GWP Set       │ plus the GWP set (provenance input)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Unit Validation │ Validates the input unit and converts the quantity to
│ & Normalization │ the factor's reference unit (e.g. Gallons -> Litres: * 3.785411784)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Raw Gas Calc   │ raw_gas_emission_kg = normalized_quantity * factor_value
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ GWP Resolution  │ Resolves GWP_100yr value for specific gas from tenant GWP set (AR4, AR5, AR6)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   CO2e Metric   │ co2e_kg = raw_gas_emission_kg * GWP_value
│   Aggregation   │ co2e_tonnes = co2e_kg / 1,000
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Gas-Level Rows  │ One calculation_gas_result per gas (CO2, CH4, N2O) and
│ & Emission      │ the ACTIVE ledger row (prior active record superseded);
│ Record          │ Scope 2 rows carry their location/market classification
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Snapshot Freeze │ Immutable calculation snapshot (full lineage) + SHA-256
│                 │ deterministic hash — integrity checksum, not a seal
└─────────────────┘
```

---

## 3. Unit Normalization Matrix

All conversions utilize explicit, deterministic conversion ratios:

| Source Unit | Target Unit | Multiplier / Formula | Domain |
| :--- | :--- | :--- | :--- |
| `MWh` | `kWh` | `1,000` | Energy |
| `kWh` | `MWh` | `0.001` | Energy |
| `Therms` | `kWh` | `29.3001` | Energy |
| `Gallons (US)` | `Litres` | `3.785411784` | Volume |
| `Litres` | `Gallons (US)` | `1 / 3.785411784` | Volume |
| `m3 (Natural Gas)` | `kWh` | `10.55` | Energy |
| `KG` | `Metric Tonnes` | `0.001` | Mass |
| `Metric Tonnes` | `KG` | `1,000` | Mass |
| any unit | same unit | `1` (identity) | any |

An unsupported pair is a hard error — `400 VALIDATION_ERROR`, `Unit conversion from X to Y is not supported.` — the engine never silently multiplies by 1 across *different* units. Unit matching is case-insensitive with common aliases (`GAL`/`GALLON`, `L`/`LITRE`, `T`/`KILOGRAMS`, …).

---

## 4. Scope Categorization Rules

### 4.1 Scope 1: Direct Emissions
1. **Stationary Combustion**: Boilers, furnaces, turbines, emergency backup generators.
   - Example: Natural Gas ($m^3$ or $kWh$), Fuel Oil ($L$), Diesel ($L$).
2. **Mobile Combustion**: Company-owned vehicle fleet, delivery vans, aviation.
   - Example: Fleet Diesel ($L$), Fleet Gasoline ($L$), Jet Fuel ($L$).
3. **Process Emissions**: Physical or chemical production processes.
   - Example: Calcination in cement production, chemical synthesis.
4. **Fugitive Emissions**: Intentional or unintentional leaks from HVAC, cooling systems, or pipelines.
   - Example: Refrigerant top-ups ($kg$ R-410A, R-134a, $SF_6$).

### 4.2 Scope 2: Indirect Emissions & Dual-Reporting Mandate
Under GHG Protocol Scope 2 Guidance, organizations operating in markets with contractual instruments must report using two methods:
1. **Location-Based Method**: Reflects the average emission intensity of grids on which energy consumption occurs (e.g., US eGRID subregions, national average grid factors).
2. **Market-Based Method**: Reflects emissions from electricity that organizations have purposefully chosen (e.g., supplier-specific tariffs, Guarantees of Origin, Renewable Energy Certificates / RECs, Power Purchase Agreements / PPAs).

> [!CRITICAL]
> **Strict Non-Aggregation Rule**: Location-based and market-based numbers must remain separate.
> **NEVER**: $Location + Market = Total$.
> When displaying organizational totals, the system presents:
> - **Total (Location-Based)** = $Scope 1 + Scope 2 (Location) + Scope 3$
> - **Total (Market-Based)** = $Scope 1 + Scope 2 (Market) + Scope 3$
> If market-based data is unavailable for a given facility or period, it is flagged explicitly as `NOT_AVAILABLE` or `RESIDUAL_MIX_REQUIRED`. The system **never** silently copies the location-based factor into market-based reporting.

---

## 5. Reference GWP Sets (100-Year Time Horizon)

| Gas | Chemical Formula | IPCC AR4 (2007) | IPCC AR5 (2013) | IPCC AR6 (2021) |
| :--- | :--- | :---: | :---: | :---: |
| Carbon Dioxide | $\text{CO}_2$ | 1 | 1 | 1 |
| Methane | $\text{CH}_4$ | 25 | 28 | 27.9 |
| Nitrous Oxide | $\text{N}_2\text{O}$ | 298 | 265 | 273 |
| Hydrofluorocarbon-134a | $\text{HFC-134a}$ | 1,430 | 1,300 | 1,530 |
| Hydrofluorocarbon-32 | $\text{HFC-32}$ | 675 | 677 | 771 |
| Sulfur Hexafluoride | $\text{SF}_6$ | 22,800 | 23,500 | 25,200 |

---

## 6. Engine Execution & Persistence (Phase 6, ADR-017)

Production calculation is owned by the Spring Boot backend - `CalculationService` (contract & batch orchestration), `GhgCalculationEngine` (pure deterministic arithmetic) and `CalculationPersistence` (the single transactional writer). The Node parity oracle was decommissioned in Phase 10.5 and survives only in Git history.

- **Run** (`POST /calculations/run`) reads the activity, its tenant anchors and the factor and GWP reference data *outside* any transaction, then persists in **one** transaction: the `calculation_gas_results` rows (engine order CO₂, CH₄, N₂O), the `calculations` snapshot, the `emission_records` row plus supersession of the activity's prior active record, and the activity's `CALCULATED` status. A duplicate deterministic hash (re-execution) rolls the whole transaction back — the ledger never holds two identical calculations.
- **Batch** (`POST /calculations/batch-run`) runs one transaction per activity (independent, abort-on-error); activities with a missing factor, missing GWP set, unsupported unit or an audit-frozen period are reported as unprocessed (`total − processed`), never as failures.
- **Snapshot**: original + normalized quantity and units, conversion factor, factor id/version/value/unit/source/year, GWP set id/name, gas-level results, calculation hash and timestamp — all immutable. A later active factor version therefore cannot change history: supersession of `emission_records` rows is the only ledger mutation.
- **Deterministic hash**: `SHA-256(activityId|factorVersionId|gwpSetId|plain(normalized)|plain(tonnes))` — an **integrity checksum for engine determinism, not a cryptographic seal**.
- **Scope 2**: location-based and market-based records remain separate rows (ADR-002); no market value is copied from location results and no location+market sum is ever used. Records without a `scope2Type` classification never enter a Scope 2 sum.
- **Audit freeze**: a governed Phase 5 lock rejects writes with `409 AUDIT_LOCKED` (run) and skips them in batch — the audit's certified history cannot be mutated by accounting APIs.
- **Methodology** is reference data, not provenance: `GET /reference/methodologies` exposes `calculation_methodologies` (`GHG_PROTOCOL_CORP`, `ISO_14064_1`) additively, while `calculations` keeps ADR-008's snapshot shape (no methodology column; V9 is forbidden) — no calculation claims a methodology it cannot prove.
