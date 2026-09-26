-- CarbonFlow V4: Scope-domain integrity constraints

BEGIN;

CREATE UNIQUE INDEX uq_facilities_organization_code
    ON facilities (organization_id, facility_code);

ALTER TABLE facilities
    ADD CONSTRAINT chk_facilities_floor_area_nonnegative
    CHECK (floor_area_m2 IS NULL OR floor_area_m2 >= 0);

ALTER TABLE legal_entities
    ADD CONSTRAINT chk_legal_entities_ownership_range
    CHECK (ownership_percentage IS NULL OR (ownership_percentage >= 0 AND ownership_percentage <= 100));

COMMIT;
