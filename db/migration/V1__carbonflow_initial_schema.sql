-- CarbonFlow Flyway Migration V1: Initial Enterprise Schema
-- Conforming to UUID primary keys, check constraints, foreign keys, and indexes.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ==========================================
-- 1. IDENTITY & TENANCY
-- ==========================================
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    tax_id VARCHAR(100),
    country VARCHAR(10) NOT NULL DEFAULT 'US',
    industry VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE organization_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    consolidation_approach VARCHAR(50) NOT NULL DEFAULT 'OPERATIONAL_CONTROL' 
        CHECK (consolidation_approach IN ('OPERATIONAL_CONTROL', 'FINANCIAL_CONTROL', 'EQUITY_SHARE')),
    base_year INT NOT NULL DEFAULT 2023,
    currency VARCHAR(10) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_org_settings UNIQUE (organization_id)
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE organization_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_org_user UNIQUE (organization_id, user_id)
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- 2. ORGANIZATIONAL BOUNDARIES & FACILITIES
-- ==========================================
CREATE TABLE legal_entities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    jurisdiction VARCHAR(100) NOT NULL,
    registration_number VARCHAR(100),
    ownership_percentage NUMERIC(5,2) DEFAULT 100.00,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE facilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    legal_entity_id UUID REFERENCES legal_entities(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    facility_code VARCHAR(50) NOT NULL,
    facility_type VARCHAR(100) NOT NULL DEFAULT 'MANUFACTURING'
        CHECK (facility_type IN ('MANUFACTURING', 'OFFICE', 'DATA_CENTER', 'WAREHOUSE', 'RETAIL', 'LOGISTICS')),
    country VARCHAR(10) NOT NULL,
    state_province VARCHAR(100),
    grid_region VARCHAR(100),
    floor_area_m2 NUMERIC(12,2),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    facility_id UUID NOT NULL REFERENCES facilities(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reporting_periods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'UNDER_AUDIT', 'LOCKED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_period_dates CHECK (end_date >= start_date)
);

CREATE TABLE organizational_boundaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE CASCADE,
    consolidation_approach VARCHAR(50) NOT NULL DEFAULT 'OPERATIONAL_CONTROL'
        CHECK (consolidation_approach IN ('OPERATIONAL_CONTROL', 'FINANCIAL_CONTROL', 'EQUITY_SHARE')),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE boundary_facilities (
    boundary_id UUID NOT NULL REFERENCES organizational_boundaries(id) ON DELETE CASCADE,
    facility_id UUID NOT NULL REFERENCES facilities(id) ON DELETE CASCADE,
    PRIMARY KEY (boundary_id, facility_id)
);

-- ==========================================
-- 3. REFERENCE DATA (GWP, METHODOLOGY, FACTORS)
-- ==========================================
CREATE TABLE gwp_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    assessment_report VARCHAR(50) NOT NULL,
    publication_year INT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE gwp_values (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gwp_set_id UUID NOT NULL REFERENCES gwp_sets(id) ON DELETE CASCADE,
    gas VARCHAR(50) NOT NULL,
    gwp_100yr NUMERIC(10,2) NOT NULL,
    CONSTRAINT uq_gwp_gas UNIQUE (gwp_set_id, gas)
);

CREATE TABLE calculation_methodologies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    version VARCHAR(50) NOT NULL,
    description TEXT
);

CREATE TABLE emission_factors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('SCOPE_1', 'SCOPE_2', 'SCOPE_3')),
    category VARCHAR(100) NOT NULL,
    activity_type VARCHAR(100) NOT NULL,
    fuel_or_activity VARCHAR(150) NOT NULL,
    input_unit VARCHAR(50) NOT NULL
);

CREATE TABLE emission_factor_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    emission_factor_id UUID NOT NULL REFERENCES emission_factors(id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    co2_factor NUMERIC(14,8) NOT NULL DEFAULT 0,
    ch4_factor NUMERIC(14,8) NOT NULL DEFAULT 0,
    n2o_factor NUMERIC(14,8) NOT NULL DEFAULT 0,
    co2e_factor NUMERIC(14,8) NOT NULL,
    factor_unit VARCHAR(50) NOT NULL,
    source VARCHAR(150) NOT NULL,
    source_year INT NOT NULL,
    geography VARCHAR(100) NOT NULL DEFAULT 'GLOBAL',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'ARCHIVED')),
    effective_start DATE NOT NULL,
    effective_end DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_factor_ver UNIQUE (emission_factor_id, version_number)
);

-- ==========================================
-- 4. ACTIVITY DATA & REQUESTS
-- ==========================================
CREATE TABLE activity_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    facility_id UUID NOT NULL REFERENCES facilities(id) ON DELETE RESTRICT,
    department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('SCOPE_1', 'SCOPE_2', 'SCOPE_3')),
    category VARCHAR(100) NOT NULL,
    activity_type VARCHAR(100) NOT NULL,
    quantity NUMERIC(16,4) NOT NULL CHECK (quantity >= 0),
    unit VARCHAR(50) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    source VARCHAR(150) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'VALIDATED', 'CALCULATED', 'LOCKED')),
    notes TEXT,
    submitted_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE data_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE CASCADE,
    facility_id UUID NOT NULL REFERENCES facilities(id) ON DELETE CASCADE,
    category VARCHAR(100) NOT NULL,
    assignee_id UUID REFERENCES users(id) ON DELETE SET NULL,
    due_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUBMITTED', 'REJECTED', 'COMPLETED')),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- 5. CALCULATIONS & EMISSION LEDGER
-- ==========================================
CREATE TABLE calculations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    activity_data_id UUID NOT NULL REFERENCES activity_data(id) ON DELETE RESTRICT,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    factor_version_id UUID NOT NULL REFERENCES emission_factor_versions(id) ON DELETE RESTRICT,
    gwp_set_id UUID NOT NULL REFERENCES gwp_sets(id) ON DELETE RESTRICT,
    original_quantity NUMERIC(16,4) NOT NULL,
    original_unit VARCHAR(50) NOT NULL,
    normalized_quantity NUMERIC(16,8) NOT NULL,
    normalized_unit VARCHAR(50) NOT NULL,
    factor_value NUMERIC(14,8) NOT NULL,
    total_co2e_kg NUMERIC(18,4) NOT NULL,
    total_co2e_tonnes NUMERIC(18,6) NOT NULL,
    calculation_hash VARCHAR(64) NOT NULL,
    calculated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    calculated_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE calculation_gas_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    calculation_id UUID NOT NULL REFERENCES calculations(id) ON DELETE CASCADE,
    gas VARCHAR(50) NOT NULL,
    raw_gas_emission_kg NUMERIC(16,8) NOT NULL,
    gwp_applied NUMERIC(10,2) NOT NULL,
    co2e_kg NUMERIC(18,4) NOT NULL
);

CREATE TABLE emission_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    facility_id UUID NOT NULL REFERENCES facilities(id) ON DELETE RESTRICT,
    calculation_id UUID NOT NULL REFERENCES calculations(id) ON DELETE RESTRICT,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('SCOPE_1', 'SCOPE_2', 'SCOPE_3')),
    category VARCHAR(100) NOT NULL,
    scope2_type VARCHAR(50) CHECK (scope2_type IN ('LOCATION_BASED', 'MARKET_BASED', NULL)),
    co2e_tonnes NUMERIC(18,6) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'VOIDED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- 6. AUDITS & GOVERNANCE WORKFLOW
-- ==========================================
CREATE TABLE carbon_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'DATA_COLLECTION', 'VALIDATION', 'REVIEW', 'APPROVED', 'AUDIT_READY', 'LOCKED')),
    initiated_by UUID REFERENCES users(id) ON DELETE SET NULL,
    approved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    locked_at TIMESTAMP WITH TIME ZONE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_audit_period UNIQUE (organization_id, reporting_period_id)
);

CREATE TABLE audit_checklist_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    is_mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    is_satisfied BOOLEAN NOT NULL DEFAULT FALSE,
    verified_by UUID REFERENCES users(id) ON DELETE SET NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    notes TEXT,
    CONSTRAINT uq_audit_item UNIQUE (audit_id, code)
);

CREATE TABLE review_findings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE CASCADE,
    activity_data_id UUID REFERENCES activity_data(id) ON DELETE SET NULL,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    comment_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE correction_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE CASCADE,
    activity_data_id UUID NOT NULL REFERENCES activity_data(id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    requested_by UUID REFERENCES users(id) ON DELETE SET NULL,
    is_resolved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE CASCADE,
    approver_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(100) NOT NULL,
    signature_hash VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_lock_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    audit_id UUID NOT NULL REFERENCES carbon_audits(id) ON DELETE RESTRICT,
    locked_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    inventory_hash VARCHAR(64) NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- 7. EVIDENCE MANAGEMENT
-- ==========================================
CREATE TABLE evidence_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL CHECK (file_size_bytes <= 26214400), -- 25MB
    mime_type VARCHAR(100) NOT NULL,
    sha256_hash VARCHAR(64) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    uploaded_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE evidence_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evidence_record_id UUID NOT NULL REFERENCES evidence_records(id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    sha256_hash VARCHAR(64) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_evidence_version UNIQUE (evidence_record_id, version_number)
);

CREATE TABLE evidence_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evidence_record_id UUID NOT NULL REFERENCES evidence_records(id) ON DELETE CASCADE,
    entity_type VARCHAR(50) NOT NULL CHECK (entity_type IN ('ACTIVITY_DATA', 'AUDIT', 'FACILITY')),
    entity_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- 8. INVENTORY, TARGETS & REDUCTION
-- ==========================================
CREATE TABLE inventory_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    reporting_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    audit_id UUID REFERENCES carbon_audits(id) ON DELETE SET NULL,
    scope1_co2e_t NUMERIC(18,6) NOT NULL,
    scope2_location_co2e_t NUMERIC(18,6) NOT NULL,
    scope2_market_co2e_t NUMERIC(18,6) NOT NULL,
    biogenic_co2e_t NUMERIC(18,6) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'REVERTED')),
    snapshot_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE carbon_targets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    baseline_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    target_period_id UUID NOT NULL REFERENCES reporting_periods(id) ON DELETE RESTRICT,
    baseline_value_t NUMERIC(18,4) NOT NULL,
    target_value_t NUMERIC(18,4) NOT NULL,
    reduction_percentage NUMERIC(5,2) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ON_TRACK'
        CHECK (status IN ('ON_TRACK', 'BEHIND', 'ACHIEVED', 'EXPIRED')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reduction_projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    target_id UUID REFERENCES carbon_targets(id) ON DELETE SET NULL,
    facility_id UUID REFERENCES facilities(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    baseline_t NUMERIC(18,4) NOT NULL,
    expected_reduction_t NUMERIC(18,4) NOT NULL,
    actual_reduction_t NUMERIC(18,4) NOT NULL DEFAULT 0,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PLANNED'
        CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    owner_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- INDEXES
-- ==========================================
CREATE INDEX idx_memberships_tenant_user ON organization_memberships (organization_id, user_id);
CREATE INDEX idx_activity_tenant_period ON activity_data (organization_id, reporting_period_id, status);
CREATE INDEX idx_calculations_activity ON calculations (activity_data_id);
CREATE INDEX idx_emissions_tenant_period ON emission_records (organization_id, reporting_period_id, status, scope);
CREATE INDEX idx_evidence_tenant ON evidence_records (organization_id);
CREATE INDEX idx_audits_tenant_period ON carbon_audits (organization_id, reporting_period_id);
CREATE INDEX idx_inventory_tenant_period ON inventory_snapshots (organization_id, reporting_period_id);
CREATE INDEX idx_targets_org ON carbon_targets (organization_id);
CREATE INDEX idx_reduction_org ON reduction_projects (organization_id);
