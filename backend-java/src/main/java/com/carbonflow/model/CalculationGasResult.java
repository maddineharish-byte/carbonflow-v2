package com.carbonflow.model;

import com.carbonflow.dto.PlainBigDecimalSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;

/**
 * One gas-level row of {@code calculation_gas_results} — the per-gas trace the
 * Node reference returns inside {@code calculation.gasResults} ({@code CO2},
 * {@code CH4}, {@code N2O} for split factors, {@code CO2e_COMPOSITE} for
 * single-value factors such as refrigerants).
 *
 * <p>{@code rawGasEmissionKg} and {@code co2eKg} are stored at the schema
 * scales (8 and 4 dp); {@code gwpApplied} records the GWP value actually used
 * from the selected set — historical rows never drift when GWP sets change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CalculationGasResult {

    private String gas;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal rawGasEmissionKg;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal gwpApplied;
    @JsonSerialize(using = PlainBigDecimalSerializer.class)
    private BigDecimal co2eKg;

    public CalculationGasResult() {
    }

    public CalculationGasResult(String gas, BigDecimal rawGasEmissionKg,
                                BigDecimal gwpApplied, BigDecimal co2eKg) {
        this.gas = gas;
        this.rawGasEmissionKg = rawGasEmissionKg;
        this.gwpApplied = gwpApplied;
        this.co2eKg = co2eKg;
    }

    public String getGas() { return gas; }
    public void setGas(String gas) { this.gas = gas; }

    public BigDecimal getRawGasEmissionKg() { return rawGasEmissionKg; }
    public void setRawGasEmissionKg(BigDecimal rawGasEmissionKg) { this.rawGasEmissionKg = rawGasEmissionKg; }

    public BigDecimal getGwpApplied() { return gwpApplied; }
    public void setGwpApplied(BigDecimal gwpApplied) { this.gwpApplied = gwpApplied; }

    public BigDecimal getCo2eKg() { return co2eKg; }
    public void setCo2eKg(BigDecimal co2eKg) { this.co2eKg = co2eKg; }
}
