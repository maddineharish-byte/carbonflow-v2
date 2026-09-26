package com.carbonflow.service;

import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.EmissionFactor;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.repository.DataStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

@Service
public class GhgCalculationEngine {

    private final DataStore dataStore;

    // Unit conversion constants
    public static final BigDecimal GALLON_TO_LITRE = new BigDecimal("3.785411784");
    public static final BigDecimal THERM_TO_KWH = new BigDecimal("29.3071");
    public static final BigDecimal MWH_TO_KWH = new BigDecimal("1000.0");
    public static final BigDecimal METRIC_TON_TO_KG = new BigDecimal("1000.0");
    public static final BigDecimal KG_TO_TONNE = new BigDecimal("1000.0");

    public GhgCalculationEngine(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    public static class NormalizedActivity {
        public final BigDecimal quantity;
        public final String unit;

        public NormalizedActivity(BigDecimal quantity, String unit) {
            this.quantity = quantity;
            this.unit = unit;
        }
    }

    /**
     * Converts raw input quantity into standard factor unit
     */
    public NormalizedActivity normalizeUnit(BigDecimal rawQuantity, String rawUnit, String targetUnit) {
        String ru = rawUnit.toUpperCase().trim();
        String tu = targetUnit.toUpperCase().trim();

        if (ru.equals(tu)) {
            return new NormalizedActivity(rawQuantity, rawUnit);
        }

        // Volume
        if (ru.equals("GALLONS") || ru.equals("GAL")) {
            if (tu.equals("LITRES") || tu.equals("L")) {
                return new NormalizedActivity(rawQuantity.multiply(GALLON_TO_LITRE), "Litres");
            }
        }

        // Energy
        if (ru.equals("THERMS") || ru.equals("THERM")) {
            if (tu.equals("KWH")) {
                return new NormalizedActivity(rawQuantity.multiply(THERM_TO_KWH), "kWh");
            }
        }
        if (ru.equals("MWH")) {
            if (tu.equals("KWH")) {
                return new NormalizedActivity(rawQuantity.multiply(MWH_TO_KWH), "kWh");
            }
        }

        // Mass
        if (ru.equals("MT") || ru.equals("TONNES")) {
            if (tu.equals("KG")) {
                return new NormalizedActivity(rawQuantity.multiply(METRIC_TON_TO_KG), "KG");
            }
        }

        // Default identity if no conversion matched
        return new NormalizedActivity(rawQuantity, rawUnit);
    }

    /**
     * Executes deterministic GHG calculation and produces an immutable calculation record with SHA-256 seal
     */
    public Calculation executeCalculation(ActivityData activity, EmissionFactor factor, String executedByUserId) {
        NormalizedActivity norm = normalizeUnit(activity.getQuantity(), activity.getUnit(), factor.getActivityUnit());

        // kgCO2e = normalizedQuantity * factorValue
        BigDecimal calculatedKgCO2e = norm.quantity.multiply(factor.getFactorValue()).setScale(6, RoundingMode.HALF_UP);

        // tonnesCO2e = kgCO2e / 1000
        BigDecimal calculatedTonnesCO2e = calculatedKgCO2e.divide(KG_TO_TONNE, 6, RoundingMode.HALF_UP);

        String formula = String.format("%s %s (norm: %s %s) × %s %s = %s kgCO2e (%s tCO2e)",
                activity.getQuantity().toPlainString(), activity.getUnit(),
                norm.quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(), norm.unit,
                factor.getFactorValue().toPlainString(), factor.getSourceName(),
                calculatedKgCO2e.toPlainString(), calculatedTonnesCO2e.toPlainString());

        String hash = computeCalculationHash(activity.getOrganizationId(), activity.getId(), factor.getId(),
                activity.getQuantity(), factor.getFactorValue(), calculatedKgCO2e);

        String calcId = UUID.randomUUID().toString();
        Calculation calc = new Calculation(
                calcId,
                activity.getOrganizationId(),
                activity.getId(),
                factor.getId(),
                activity.getQuantity(),
                activity.getUnit(),
                norm.quantity,
                norm.unit,
                factor.getFactorValue(),
                calculatedKgCO2e,
                calculatedTonnesCO2e,
                formula,
                hash,
                executedByUserId,
                Instant.now()
        );

        // Persist calculation
        dataStore.calculations.put(calc.getId(), calc);

        // Update activity status & calculation link
        activity.setStatus("CALCULATED");
        activity.setCalculationId(calc.getId());

        // Create or update emission ledger record
        String recId = UUID.randomUUID().toString();
        EmissionRecord rec = new EmissionRecord(
                recId,
                activity.getOrganizationId(),
                activity.getFacilityId(),
                activity.getReportingPeriodId(),
                calc.getId(),
                factor.getScope(),
                factor.getCategory(),
                factor.getScope2Type(),
                calculatedTonnesCO2e,
                "ACTIVE",
                Instant.now()
        );
        dataStore.emissionRecords.put(rec.getId(), rec);

        return calc;
    }

    private String computeCalculationHash(String orgId, String actId, String factorId, BigDecimal qty, BigDecimal factorVal, BigDecimal kgResult) {
        String payload = String.format("%s:%s:%s:%s:%s:%s", orgId, actId, factorId, qty.toPlainString(), factorVal.toPlainString(), kgResult.toPlainString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing in JVM", e);
        }
    }
}
