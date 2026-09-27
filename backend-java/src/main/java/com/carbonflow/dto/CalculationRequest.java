package com.carbonflow.dto;

/**
 * {@code POST /api/v1/calculations/run} payload.
 *
 * <p>The three fields are deliberately typed {@code Object}: the Node route
 * treats a non-string {@code activityDataId} as a missing required field
 * ({@code 400 VALIDATION_ERROR "activityDataId is required."}) and silently
 * ignores non-string {@code factorVersionId}/{@code gwpSetId}. Declaring them
 * as {@code String} would let Jackson coerce a JSON number into an id and
 * change which contract error fires, so the service performs the
 * {@code instanceof String} checks Node performs.
 */
public class CalculationRequest {

    private Object activityDataId;
    private Object factorVersionId;
    private Object gwpSetId;

    public Object getActivityDataId() { return activityDataId; }
    public void setActivityDataId(Object activityDataId) { this.activityDataId = activityDataId; }

    public Object getFactorVersionId() { return factorVersionId; }
    public void setFactorVersionId(Object factorVersionId) { this.factorVersionId = factorVersionId; }

    public Object getGwpSetId() { return gwpSetId; }
    public void setGwpSetId(Object gwpSetId) { this.gwpSetId = gwpSetId; }

    /** Node: {@code typeof value === 'string' ? value : undefined}. */
    public static String optionalString(Object value) {
        return value instanceof String s && !s.isEmpty() ? s : null;
    }
}
