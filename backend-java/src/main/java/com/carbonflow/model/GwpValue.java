package com.carbonflow.model;

import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;

/**
 * One row of {@code gwp_values}: the 100-year GWP of a gas within a set,
 * in Node's wire shape ({@code mapGwpValue}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GwpValue {

    private String id;
    private String gwpSetId;
    private String gas;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal gwp100yr;

    public GwpValue() {
    }

    public GwpValue(String id, String gwpSetId, String gas, BigDecimal gwp100yr) {
        this.id = id;
        this.gwpSetId = gwpSetId;
        this.gas = gas;
        this.gwp100yr = gwp100yr;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGwpSetId() { return gwpSetId; }
    public void setGwpSetId(String gwpSetId) { this.gwpSetId = gwpSetId; }

    public String getGas() { return gas; }
    public void setGas(String gas) { this.gas = gas; }

    public BigDecimal getGwp100yr() { return gwp100yr; }
    public void setGwp100yr(BigDecimal gwp100yr) { this.gwp100yr = gwp100yr; }
}
