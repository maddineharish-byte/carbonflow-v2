package com.carbonflow.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Deterministic activity-unit → factor-unit normalization, ported pair-for-pair
 * from the Node reference's {@code normalizeUnits} ({@code server/calc.ts}).
 *
 * <p><b>Precision:</b> every product/division runs under a MathContext of 28
 * significant digits, HALF_UP — the same configuration Decimal.js is created
 * with in the reference engine ({@code Decimal.set({precision: 28, rounding:
 * ROUND_HALF_UP})}), so both engines agree digit-for-digit on ordinary input.
 *
 * <p><b>Conversion table (ported exactly, uppercased/trimmed matching):</b>
 * <ul>
 *   <li>identical units → factor 1, target (uppercased) as result unit</li>
 *   <li>MWh → kWh × 1000; kWh → MWh × 0.001</li>
 *   <li>Therms → kWh × 29.3001; m³ → kWh × 10.55</li>
 *   <li>Gallons/Gallon/Gal → Litres/Litre/L × 3.785411784</li>
 *   <li>Litres/Litre/L → Gallons ÷ 3.785411784</li>
 *   <li>KG/Kilograms → Metric Tonnes/Tonnes/T × 0.001</li>
 *   <li>Tonnes/Metric Tonnes/T → KG/Kilograms × 1000</li>
 * </ul>
 *
 * <p><b>Documented deviation (ADR-017):</b> the Node engine falls through to a
 * silent factor of {@code 1} for any pair the table does not cover, which
 * silently mis-states emissions (e.g. {@code LITRES → FATHOMS}). This service
 * rejects unhandled pairs with {@code 400 VALIDATION_ERROR} instead — a
 * calculation can only proceed when the conversion factor is known. The
 * supported table itself is unchanged.
 */
@Service
public class UnitConversionService {

    /** 28 significant digits, HALF_UP — Decimal.js parity. */
    private static final MathContext MC = new MathContext(28, RoundingMode.HALF_UP);

    private static final BigDecimal GALLON_TO_LITRE = new BigDecimal("3.785411784");

    /** Thrown for a unit pair the reference table does not cover (batch skips it). */
    public static class UnsupportedUnitConversionException extends AuthException {
        public UnsupportedUnitConversionException(String message) {
            super("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
        }
    }

    /** Result of a normalization: value, result unit and the factor applied. */
    public record UnitNormalization(BigDecimal normalizedQuantity, String normalizedUnit,
                                    BigDecimal conversionFactor) {
    }

    /**
     * Normalizes {@code quantity} from {@code inputUnit} to {@code targetUnit}
     * (the factor's reference unit).
     *
     * @throws UnsupportedUnitConversionException when the pair is not in the table
     */
    public UnitNormalization normalize(BigDecimal quantity, String inputUnit, String targetUnit) {
        String from = inputUnit.toUpperCase().trim();
        String to = targetUnit.toUpperCase().trim();

        if (from.equals(to)) {
            return new UnitNormalization(quantity, to, BigDecimal.ONE);
        }

        // Energy conversions (calc.ts order).
        if (from.equals("MWH") && to.equals("KWH")) {
            return product(quantity, new BigDecimal("1000"), "kWh");
        }
        if (from.equals("KWH") && to.equals("MWH")) {
            return product(quantity, new BigDecimal("0.001"), "MWh");
        }
        if (from.equals("THERMS") && to.equals("KWH")) {
            return product(quantity, new BigDecimal("29.3001"), "kWh");
        }
        if (from.equals("M3") && to.equals("KWH")) {
            return product(quantity, new BigDecimal("10.55"), "kWh");
        }

        // Volume conversions.
        if ((from.equals("GALLONS") || from.equals("GALLON") || from.equals("GAL"))
                && (to.equals("LITRES") || to.equals("LITRE") || to.equals("L"))) {
            return product(quantity, GALLON_TO_LITRE, "Litres");
        }
        if ((from.equals("LITRES") || from.equals("LITRE") || from.equals("L"))
                && !from.equals(to) && to.equals("GALLONS")) {
            BigDecimal factor = BigDecimal.ONE.divide(GALLON_TO_LITRE, MC);
            return new UnitNormalization(quantity.multiply(factor, MC), "Gallons", factor);
        }

        // Mass conversions.
        if ((from.equals("KG") || from.equals("KILOGRAMS"))
                && (to.equals("METRIC TONNES") || to.equals("TONNES") || to.equals("T"))) {
            return product(quantity, new BigDecimal("0.001"), "Metric Tonnes");
        }
        if ((from.equals("TONNES") || from.equals("METRIC TONNES") || from.equals("T"))
                && (to.equals("KG") || to.equals("KILOGRAMS"))) {
            return product(quantity, new BigDecimal("1000"), "KG");
        }

        throw new UnsupportedUnitConversionException(
                "Unit conversion from " + inputUnit + " to " + targetUnit + " is not supported.");
    }

    private static UnitNormalization product(BigDecimal quantity, BigDecimal factor, String resultUnit) {
        return new UnitNormalization(quantity.multiply(factor, MC), resultUnit, factor);
    }
}
