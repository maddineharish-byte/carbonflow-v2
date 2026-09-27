package com.carbonflow.dto;

/**
 * {@code POST /api/v1/calculations/batch-run} payload: an optional
 * {@code reportingPeriodId}.
 *
 * <p>Typed {@code Object} for the same parity reason as
 * {@link CalculationRequest}: Node rejects a non-string
 * {@code reportingPeriodId} with {@code 400 VALIDATION_ERROR
 * "reportingPeriodId must be a string."} where a {@code String} field would
 * silently coerce a JSON number.
 */
public class BatchCalculationRequest {

    private Object reportingPeriodId;

    public Object getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(Object reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }
}
