package com.carbonflow.service;

import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Calculation;
import com.carbonflow.model.CalculationGasResult;
import com.carbonflow.model.EmissionFactorVersion;
import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.GwpSet;
import com.carbonflow.model.GwpValue;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic GHG calculation engine — a pure BigDecimal port of the Node
 * reference's {@code executeCalculation} ({@code server/calc.ts}).
 *
 * <p><b>Hard rules (Phase 6):</b>
 * <ul>
 *   <li>BigDecimal only, MathContext 28 digits / HALF_UP — no float/double
 *       anywhere in the carbon path, no intermediate rounding drift: per-gas
 *       values are rounded only when written to their schema scales, the
 *       totals accumulate the unrounded products exactly (Decimal.js parity).</li>
 *   <li>The output is an immutable snapshot: original + normalized quantity,
 *       conversion factor, factor value/unit/source/version, GWP set + name,
 *       per-gas results — everything a historical calculation needs to stay
 *       stable while reference data evolves.</li>
 *   <li>Scope 2 is classified LOCATION_BASED or MARKET_BASED at execution time
 *       and persisted on the emission record; the two perspectives are never
 *       merged, never summed, and there is no combined Scope 2 output.</li>
 *   <li>SHA-256 is an integrity checksum over the snapshot inputs only — it is
 *       not proof of immutability (documented, ADR-017).</li>
 * </ul>
 *
 * <p>Unit normalization delegates to {@link UnitConversionService}; reference
 * resolution and persistence belong to {@code CalculationService}. This class
 * holds no state and touches no store.
 */
@Service
public class GhgCalculationEngine {

    /** 28 significant digits, HALF_UP — Decimal.js parity. */
    static final MathContext MC = new MathContext(28, RoundingMode.HALF_UP);

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    /** Everything resolved from reference data for one execution. */
    public record ResolvedReferences(EmissionFactorVersion factorVersion, String factorInputUnit,
                                     GwpSet gwpSet, List<GwpValue> gwpValues) {
    }

    /** Calculation + emission record produced atomically by one execution. */
    public record CalculationOutput(Calculation calculation, EmissionRecord emissionRecord) {
    }

    private final UnitConversionService unitConversions;

    public GhgCalculationEngine(UnitConversionService unitConversions) {
        this.unitConversions = unitConversions;
    }

    /**
     * Executes the canonical flow: unit normalization → gas breakdown with the
     * selected GWP set → totals → snapshot hash → Scope 2 classification.
     *
     * @throws AuthException VALIDATION_ERROR for an uncovered unit pair,
     *                       GWP_SET_NOT_FOUND when the selected set has no
     *                       value for a gas the factor emits
     */
    public CalculationOutput executeCalculation(ActivityData activity,
                                                ResolvedReferences references,
                                                String userId) {
        EmissionFactorVersion version = references.factorVersion();

        // 1. Normalize units (exact conversion table; errors on unknown pairs).
        UnitConversionService.UnitNormalization norm =
                unitConversions.normalize(activity.getQuantity(), activity.getUnit(),
                        references.factorInputUnit());
        BigDecimal normalizedQty = norm.normalizedQuantity();

        // 2. GWP lookup — the selected set must carry every gas the factor emits.
        Map<String, BigDecimal> gwpMap = new HashMap<>();
        for (GwpValue value : references.gwpValues()) {
            gwpMap.put(value.getGas().toUpperCase(Locale.ROOT), value.getGwp100yr());
        }

        List<CalculationGasResult> gasResults = new ArrayList<>();
        BigDecimal totalCo2eKg = BigDecimal.ZERO;

        boolean splitFactors = version.getCo2Factor().signum() > 0
                || version.getCh4Factor().signum() > 0
                || version.getN2oFactor().signum() > 0;

        if (splitFactors) {
            totalCo2eKg = BigDecimal.ZERO;
            if (version.getCo2Factor().signum() > 0) {
                GasEmission gas = gasEmission(normalizedQty, version.getCo2Factor(), "CO2", gwpMap);
                gasResults.add(gas.result());
                totalCo2eKg = totalCo2eKg.add(gas.unroundedCo2e(), MC);
            }
            if (version.getCh4Factor().signum() > 0) {
                GasEmission gas = gasEmission(normalizedQty, version.getCh4Factor(), "CH4", gwpMap);
                gasResults.add(gas.result());
                totalCo2eKg = totalCo2eKg.add(gas.unroundedCo2e(), MC);
            }
            if (version.getN2oFactor().signum() > 0) {
                GasEmission gas = gasEmission(normalizedQty, version.getN2oFactor(), "N2O", gwpMap);
                gasResults.add(gas.result());
                totalCo2eKg = totalCo2eKg.add(gas.unroundedCo2e(), MC);
            }
        } else {
            // Composite CO2e factor (refrigerants, green tariffs) — no GWP needed.
            totalCo2eKg = normalizedQty.multiply(version.getCo2eFactor(), MC);
            gasResults.add(new CalculationGasResult(
                    "CO2e_COMPOSITE",
                    totalCo2eKg.setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.ONE,
                    totalCo2eKg.setScale(4, RoundingMode.HALF_UP)));
        }

        BigDecimal totalCo2eTonnes = totalCo2eKg.divide(THOUSAND, MC);
        String calculationId = UUID.randomUUID().toString();

        // 3. Integrity checksum over the unrounded snapshot inputs (SHA-256 hex).
        String hashPayload = activity.getId() + "|" + version.getId() + "|"
                + references.gwpSet().getId() + "|"
                + plain(normalizedQty) + "|" + plain(totalCo2eTonnes);
        String calculationHash = sha256Hex(hashPayload);

        Calculation calculation = new Calculation();
        calculation.setId(calculationId);
        calculation.setOrganizationId(activity.getOrganizationId());
        calculation.setActivityDataId(activity.getId());
        calculation.setReportingPeriodId(activity.getReportingPeriodId());
        calculation.setFactorVersionId(version.getId());
        calculation.setGwpSetId(references.gwpSet().getId());
        calculation.setOriginalQuantity(activity.getQuantity());
        calculation.setOriginalUnit(activity.getUnit());
        calculation.setNormalizedQuantity(normalizedQty.setScale(8, RoundingMode.HALF_UP));
        calculation.setNormalizedUnit(norm.normalizedUnit());
        calculation.setConversionFactor(norm.conversionFactor().setScale(10, RoundingMode.HALF_UP));
        calculation.setFactorValue(version.getCo2eFactor());
        calculation.setFactorUnit(version.getFactorUnit());
        calculation.setFactorSource(version.getSource() + " (" + version.getSourceYear() + ")");
        calculation.setFactorVersion(version.getVersionNumber());
        calculation.setGwpName(references.gwpSet().getName());
        calculation.setTotalCo2eKg(totalCo2eKg.setScale(4, RoundingMode.HALF_UP));
        calculation.setTotalCo2eTonnes(totalCo2eTonnes.setScale(6, RoundingMode.HALF_UP));
        calculation.setCalculationHash(calculationHash);
        calculation.setGasResults(gasResults);
        calculation.setCalculatedAt(Instant.now());
        calculation.setCalculatedBy(userId);
        calculation.setFactorId(version.getEmissionFactorId());

        // 4. Scope 2 dual reporting: classify, never combine.
        Scope2Method scope2Type = null;
        if (activity.getScope() == GHGScope.SCOPE_2) {
            boolean location = activity.getCategory().toUpperCase(Locale.ROOT).contains("LOCATION")
                    || activity.getActivityType().toUpperCase(Locale.ROOT).contains("GRID");
            scope2Type = location ? Scope2Method.LOCATION_BASED : Scope2Method.MARKET_BASED;
        }

        EmissionRecord emissionRecord = new EmissionRecord();
        emissionRecord.setId(UUID.randomUUID().toString());
        emissionRecord.setOrganizationId(activity.getOrganizationId());
        emissionRecord.setReportingPeriodId(activity.getReportingPeriodId());
        emissionRecord.setFacilityId(activity.getFacilityId());
        emissionRecord.setCalculationId(calculationId);
        emissionRecord.setScope(activity.getScope());
        emissionRecord.setCategory(activity.getCategory());
        emissionRecord.setScope2Type(scope2Type);
        emissionRecord.setCo2eTonnes(totalCo2eTonnes.setScale(6, RoundingMode.HALF_UP));
        emissionRecord.setStatus("ACTIVE");
        emissionRecord.setCreatedAt(Instant.now());

        return new CalculationOutput(calculation, emissionRecord);
    }

    private record GasEmission(CalculationGasResult result, BigDecimal unroundedCo2e) {
    }

    /** raw = normalized × gasFactor; co2e = raw × GWP — both at 28 digits. */
    private GasEmission gasEmission(BigDecimal normalizedQty, BigDecimal gasFactor,
                                    String gasName, Map<String, BigDecimal> gwpMap) {
        BigDecimal gwp = gwpMap.get(gasName);
        if (gwp == null) {
            throw new AuthException("GWP_SET_NOT_FOUND",
                    "The selected GWP set does not include a value for gas " + gasName + ".",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        BigDecimal rawKg = normalizedQty.multiply(gasFactor, MC);
        BigDecimal co2eKg = rawKg.multiply(gwp, MC);
        CalculationGasResult result = new CalculationGasResult(
                gasName,
                rawKg.setScale(8, RoundingMode.HALF_UP),
                gwp,
                co2eKg.setScale(4, RoundingMode.HALF_UP));
        return new GasEmission(result, co2eKg);
    }

    /** Decimal.js {@code toString()} equivalent for hash inputs. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
