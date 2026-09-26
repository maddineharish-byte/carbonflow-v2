-- CarbonFlow V6: calculation snapshot and tenant-integrity alignment
BEGIN;

-- Preserve the factor metadata used by the existing calculation engine at execution time.
ALTER TABLE calculations
    ADD COLUMN IF NOT EXISTS factor_id UUID REFERENCES emission_factors(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS factor_unit VARCHAR(50),
    ADD COLUMN IF NOT EXISTS factor_source VARCHAR(150),
    ADD COLUMN IF NOT EXISTS factor_version_number INTEGER,
    ADD COLUMN IF NOT EXISTS gwp_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS conversion_factor NUMERIC(20,10) NOT NULL DEFAULT 1;

UPDATE calculations c
   SET factor_id = efv.emission_factor_id,
       factor_unit = efv.factor_unit,
       factor_source = efv.source || ' (' || efv.source_year || ')',
       factor_version_number = efv.version_number
  FROM emission_factor_versions efv
 WHERE c.factor_version_id = efv.id;

UPDATE calculations c
   SET gwp_name = gs.name
  FROM gwp_sets gs
 WHERE c.gwp_set_id = gs.id;

ALTER TABLE calculations
    ALTER COLUMN factor_id SET NOT NULL,
    ALTER COLUMN factor_unit SET NOT NULL,
    ALTER COLUMN factor_source SET NOT NULL,
    ALTER COLUMN factor_version_number SET NOT NULL,
    ALTER COLUMN gwp_name SET NOT NULL;

ALTER TABLE calculations
    ADD CONSTRAINT chk_calculations_original_quantity_nonnegative CHECK (original_quantity >= 0),
    ADD CONSTRAINT chk_calculations_normalized_quantity_nonnegative CHECK (normalized_quantity >= 0),
    ADD CONSTRAINT chk_calculations_factor_version_positive CHECK (factor_version_number > 0),
    ADD CONSTRAINT chk_calculations_total_co2e_tonnes_nonnegative CHECK (total_co2e_tonnes >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uq_activity_data_tenant_id
    ON activity_data (organization_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_calculations_tenant_id
    ON calculations (organization_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_emission_records_tenant_id
    ON emission_records (organization_id, id);

ALTER TABLE calculations
    ADD CONSTRAINT fk_calculations_activity_tenant
        FOREIGN KEY (organization_id, activity_data_id)
        REFERENCES activity_data (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_calculations_period_tenant
        FOREIGN KEY (organization_id, reporting_period_id)
        REFERENCES reporting_periods (organization_id, id) ON DELETE RESTRICT;

ALTER TABLE emission_records
    ADD CONSTRAINT chk_emission_records_co2e_tonnes_nonnegative CHECK (co2e_tonnes >= 0),
    ADD CONSTRAINT fk_emission_records_period_tenant
        FOREIGN KEY (organization_id, reporting_period_id)
        REFERENCES reporting_periods (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_emission_records_facility_tenant
        FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_emission_records_calculation_tenant
        FOREIGN KEY (organization_id, calculation_id)
        REFERENCES calculations (organization_id, id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_calculations_tenant_activity
    ON calculations (organization_id, activity_data_id, calculated_at DESC);
CREATE INDEX IF NOT EXISTS idx_emission_records_tenant_period_scope
    ON emission_records (organization_id, reporting_period_id, scope, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_calculation_gas_results_calculation_gas
    ON calculation_gas_results (calculation_id, gas);

COMMIT;
