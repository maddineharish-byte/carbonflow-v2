-- CarbonFlow Flyway Migration V2: Reference Data Seed
-- GWP Sets, Canonical Roles, Calculation Methodologies, and Authoritative Emission Factors.

-- ==========================================
-- 1. CANONICAL ROLES
-- ==========================================
INSERT INTO roles (id, name, description) VALUES
('11111111-1111-1111-1111-111111111101', 'COMPANY_ADMIN', 'Organization administrator managing organization configuration, users, facilities, and periods.'),
('11111111-1111-1111-1111-111111111102', 'SUSTAINABILITY_MANAGER', 'Manages sustainability operations, data collection, calculations, audits, and inventories.'),
('11111111-1111-1111-1111-111111111103', 'CARBON_ACCOUNTANT', 'Works primarily with activity data, emission factors, calculations, and emission ledger.'),
('11111111-1111-1111-1111-111111111104', 'DATA_OWNER', 'Collects and maintains operational activity data and uploads primary evidence.'),
('11111111-1111-1111-1111-111111111105', 'FACILITY_MANAGER', 'Manages facility-specific operations, meters, and local data collection.'),
('11111111-1111-1111-1111-111111111106', 'REVIEWER', 'Reviews data, calculations, evidence, logs findings, and validates checklist items.'),
('11111111-1111-1111-1111-111111111107', 'MANAGEMENT', 'Views executive dashboards, inventory snapshots, targets, and reduction progress.'),
('11111111-1111-1111-1111-111111111108', 'ASSURANCE_PROVIDER', 'Read-oriented access to audit-ready records, calculations, and evidence packages.'),
('11111111-1111-1111-1111-111111111109', 'PLATFORM_ADMIN', 'Platform-level administrator managing multi-tenant infrastructure.');

-- ==========================================
-- 2. GWP REFERENCE SETS (100-YEAR HORIZON)
-- ==========================================
INSERT INTO gwp_sets (id, code, name, assessment_report, publication_year, is_default) VALUES
('22222222-2222-2222-2222-222222222201', 'IPCC_AR6', 'IPCC Sixth Assessment Report (AR6)', 'AR6', 2021, TRUE),
('22222222-2222-2222-2222-222222222202', 'IPCC_AR5', 'IPCC Fifth Assessment Report (AR5)', 'AR5', 2013, FALSE),
('22222222-2222-2222-2222-222222222203', 'IPCC_AR4', 'IPCC Fourth Assessment Report (AR4)', 'AR4', 2007, FALSE);

-- AR6 GWP Values
INSERT INTO gwp_values (gwp_set_id, gas, gwp_100yr) VALUES
('22222222-2222-2222-2222-222222222201', 'CO2', 1.00),
('22222222-2222-2222-2222-222222222201', 'CH4', 27.90),
('22222222-2222-2222-2222-222222222201', 'N2O', 273.00),
('22222222-2222-2222-2222-222222222201', 'SF6', 25200.00),
('22222222-2222-2222-2222-222222222201', 'HFC-134a', 1530.00),
('22222222-2222-2222-2222-222222222201', 'HFC-32', 771.00);

-- AR5 GWP Values
INSERT INTO gwp_values (gwp_set_id, gas, gwp_100yr) VALUES
('22222222-2222-2222-2222-222222222202', 'CO2', 1.00),
('22222222-2222-2222-2222-222222222202', 'CH4', 28.00),
('22222222-2222-2222-2222-222222222202', 'N2O', 265.00),
('22222222-2222-2222-2222-222222222202', 'SF6', 23500.00),
('22222222-2222-2222-2222-222222222202', 'HFC-134a', 1300.00),
('22222222-2222-2222-2222-222222222202', 'HFC-32', 677.00);

-- AR4 GWP Values
INSERT INTO gwp_values (gwp_set_id, gas, gwp_100yr) VALUES
('22222222-2222-2222-2222-222222222203', 'CO2', 1.00),
('22222222-2222-2222-2222-222222222203', 'CH4', 25.00),
('22222222-2222-2222-2222-222222222203', 'N2O', 298.00),
('22222222-2222-2222-2222-222222222203', 'SF6', 22800.00),
('22222222-2222-2222-2222-222222222203', 'HFC-134a', 1430.00),
('22222222-2222-2222-2222-222222222203', 'HFC-32', 675.00);

-- ==========================================
-- 3. CALCULATION METHODOLOGIES
-- ==========================================
INSERT INTO calculation_methodologies (id, code, name, version, description) VALUES
('33333333-3333-3333-3333-333333333301', 'GHG_PROTOCOL_CORP', 'GHG Protocol Corporate Accounting and Reporting Standard', 'Revised Edition', 'Standard methodology for Scope 1, Scope 2, and Scope 3 accounting.'),
('33333333-3333-3333-3333-333333333302', 'ISO_14064_1', 'ISO 14064-1:2018 Specification with guidance', '2018', 'Quantification and reporting of greenhouse gas emissions and removals.');

-- ==========================================
-- 4. EMISSION FACTORS & VERSIONS
-- ==========================================

-- Scope 1: Stationary Combustion - Natural Gas
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444401', 'SCOPE_1', 'STATIONARY_COMBUSTION', 'NATURAL_GAS', 'Natural Gas (Pipeline)', 'kWh');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555501', '44444444-4444-4444-4444-444444444401', 1, 0.18254000, 0.00024000, 0.00010000, 0.18288000, 'kgCO2e/kWh', 'UK DEFRA / BEIS', 2024, 'GLOBAL', 'ACTIVE', '2024-01-01');

-- Scope 1: Stationary Combustion - Fuel Oil / Diesel Generator
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444402', 'SCOPE_1', 'STATIONARY_COMBUSTION', 'DIESEL_GENERATOR', 'Gas Oil / Stationary Diesel', 'Litres');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555502', '44444444-4444-4444-4444-444444444402', 1, 2.68697000, 0.00085000, 0.02422000, 2.71204000, 'kgCO2e/Litre', 'UK DEFRA / BEIS', 2024, 'GLOBAL', 'ACTIVE', '2024-01-01');

-- Scope 1: Mobile Combustion - Fleet Diesel
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444403', 'SCOPE_1', 'MOBILE_COMBUSTION', 'FLEET_DIESEL', 'Diesel (100% mineral diesel fleet)', 'Litres');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555503', '44444444-4444-4444-4444-444444444403', 1, 2.51210000, 0.00007000, 0.03810000, 2.55027000, 'kgCO2e/Litre', 'US EPA Emission Hub', 2024, 'US', 'ACTIVE', '2024-01-01');

-- Scope 1: Fugitive Emissions - Refrigerant R-410A Leakage
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444404', 'SCOPE_1', 'FUGITIVE_EMISSIONS', 'REFRIGERANT_R410A', 'R-410A Refrigerant Leakage', 'KG');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555504', '44444444-4444-4444-4444-444444444404', 1, 0, 0, 0, 2088.00000000, 'kgCO2e/kg', 'IPCC AR6 Refrigerant Blend', 2024, 'GLOBAL', 'ACTIVE', '2024-01-01');

-- Scope 2: Electricity - Location-Based (US National Grid Average)
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444405', 'SCOPE_2', 'ELECTRICITY_LOCATION', 'GRID_ELECTRICITY_US', 'Grid Electricity - US Average (eGRID)', 'kWh');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555505', '44444444-4444-4444-4444-444444444405', 1, 0.38550000, 0.00003000, 0.00005000, 0.38558000, 'kgCO2e/kWh', 'US EPA eGRID', 2024, 'US', 'ACTIVE', '2024-01-01');

-- Scope 2: Electricity - Market-Based (Green Tariff / Certified Renewable)
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444406', 'SCOPE_2', 'ELECTRICITY_MARKET', 'GREEN_POWER_TARIFF', 'Supplier Green Power Contract (100% PPA/REC)', 'kWh');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555506', '44444444-4444-4444-4444-444444444406', 1, 0.00000000, 0.00000000, 0.00000000, 0.00000000, 'kgCO2e/kWh', 'Contractual Guarantee of Origin', 2024, 'GLOBAL', 'ACTIVE', '2024-01-01');

-- Scope 2: Electricity - Market-Based (Residual Mix)
INSERT INTO emission_factors (id, scope, category, activity_type, fuel_or_activity, input_unit) VALUES
('44444444-4444-4444-4444-444444444407', 'SCOPE_2', 'ELECTRICITY_MARKET', 'RESIDUAL_MIX_US', 'US Residual Mix Electricity (Unbundled)', 'kWh');

INSERT INTO emission_factor_versions (id, emission_factor_id, version_number, co2_factor, ch4_factor, n2o_factor, co2e_factor, factor_unit, source, source_year, geography, status, effective_start) VALUES
('55555555-5555-5555-5555-555555555507', '44444444-4444-4444-4444-444444444407', 1, 0.44200000, 0.00004000, 0.00006000, 0.44210000, 'kgCO2e/kWh', 'Green-e Residual Mix', 2024, 'US', 'ACTIVE', '2024-01-01');
