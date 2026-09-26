-- CarbonFlow V5: tenant integrity for activity and evidence domains
BEGIN;
CREATE UNIQUE INDEX IF NOT EXISTS uq_reporting_periods_tenant_id ON reporting_periods (organization_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_facilities_tenant_id ON facilities (organization_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_audits_tenant_id ON carbon_audits (organization_id, id);
ALTER TABLE activity_data ADD CONSTRAINT fk_activity_period_tenant FOREIGN KEY (organization_id, reporting_period_id) REFERENCES reporting_periods (organization_id, id) ON DELETE RESTRICT;
ALTER TABLE activity_data ADD CONSTRAINT fk_activity_facility_tenant FOREIGN KEY (organization_id, facility_id) REFERENCES facilities (organization_id, id) ON DELETE RESTRICT;
ALTER TABLE evidence_links ADD CONSTRAINT chk_evidence_link_entity_id CHECK (entity_id IS NOT NULL);
COMMIT;
