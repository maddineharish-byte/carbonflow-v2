# CarbonFlow — GHG Calculation Engine & Mathematical Specification

## 1. Mathematical Architecture & Precision Rules

Carbon accounting requires deterministic arithmetic identical to financial ledgers. Standard IEEE-754 floating-point operations (`0.1 + 0.2 = 0.30000000000000004`) lead to cumulative discrepancies that fail external audit verification.

### Precision Standards
- **Implementation**: Java `BigDecimal` / TypeScript `Decimal.js`.
- **Internal Computation Scale**: 8 decimal places.
- **Reporting Scale**: 4 decimal places for metric tonnes CO2e (`tCO2e`), 2 decimal places for kilogram CO2e (`kgCO2e`).
- **Rounding Mode**: `ROUND_HALF_UP` (standard accounting rounding).

---

## 2. Core Calculation Pipeline

```
┌─────────────────┐
│  Activity Data  │ (e.g. 5,000 Gallons Diesel, Stationary Generator)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Unit Validation │ Validates input unit belongs to accepted quantity domain
│ & Normalization │ Converts input quantity to factor reference unit (e.g. Gallons -> Litres: * 3.785411784)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Factor Lookup   │ Selects active factor version effective for the activity date & geography
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
│ Snapshot Freeze │ Creates immutable CalculationSnapshot with full lineage & SHA-256 trace
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Emission Record │ Inserts ACTIVE record into emission ledger
└─────────────────┘
```

---

## 3. Unit Normalization Matrix

All conversions utilize explicit, deterministic conversion ratios:

| Source Unit | Target Unit | Multiplier / Formula | Domain |
| :--- | :--- | :--- | :--- |
| `MWh` | `kWh` | `1,000` | Energy |
| `Therms` | `kWh` | `29.3001` | Energy |
| `Gallons (US)` | `Litres` | `3.785411784` | Volume |
| `m3 (Natural Gas)` | `kWh` | `10.55` | Energy |
| `KG` | `Metric Tonnes` | `0.001` | Mass |
| `Metric Tonnes` | `KG` | `1,000` | Mass |

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

## 6. Persistence & Snapshot Boundary (TASK 2.5)

Production calculation execution remains owned by `server/calc.ts`; persistence is owned by `server/calculation-repository.ts`.

- `/calculations/run` and `/calculations/batch-run` resolve active factor versions and GWP values from PostgreSQL reference tables.
- The repository transactionally writes `calculations`, `calculation_gas_results`, `emission_records`, prior active-record supersession, and the activity `CALCULATED` status.
- The persisted calculation snapshot includes original/normalized quantity and units, conversion factor, factor ID/version/value/unit/source/year, GWP set/name, gas results, hash, and timestamp.
- A later active emission-factor version cannot change a historical calculation because the snapshot fields and `factor_version_id` are immutable.
- Scope 2 location-based and market-based records remain separate rows; no market-based value is copied from location-based results, and no location+market sum is used.
- Calculation methodology fields are not part of the established engine model and were not invented.

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
