package com.carbonflow.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact conversion table of the Node engine ({@code server/calc.ts}) as a
 * plain unit test — no Spring context, no database. These are the same
 * numbers the calculation engine normalizes with, so a regression here is a
 * regression of every calculated total.
 */
class UnitConversionServiceTest {

    private final UnitConversionService conversions = new UnitConversionService();

    @Test
    void identityKeepsTheValueAndUpperCasesTheUnit() {
        UnitConversionService.UnitNormalization norm =
                conversions.normalize(new BigDecimal("1000"), "kWh", "kWh");
        assertEquals(0, norm.normalizedQuantity().compareTo(new BigDecimal("1000")));
        assertEquals("KWH", norm.normalizedUnit());
        assertEquals(0, norm.conversionFactor().compareTo(BigDecimal.ONE));

        // identity is case-insensitive on both sides
        UnitConversionService.UnitNormalization kg =
                conversions.normalize(new BigDecimal("5"), "KG", "kg");
        assertEquals(0, kg.normalizedQuantity().compareTo(new BigDecimal("5")));
        assertEquals("KG", kg.normalizedUnit());
    }

    @Test
    void energyConversionsUseTheExactTable() {
        assertConversion("2000", "kWh", "1000",
                conversions.normalize(new BigDecimal("2"), "MWh", "kWh"));
        assertConversion("2", "MWh", "0.001",
                conversions.normalize(new BigDecimal("2000"), "kWh", "MWh"));
        assertConversion("293.001", "kWh", "29.3001",
                conversions.normalize(new BigDecimal("10"), "Therms", "kWh"));
        assertConversion("10.55", "kWh", "10.55",
                conversions.normalize(new BigDecimal("1"), "m3", "kWh"));
    }

    @Test
    void volumeConversionsMatchTheReferenceConstants() {
        UnitConversionService.UnitNormalization litres =
                conversions.normalize(new BigDecimal("1000"), "Gallons", "Litres");
        assertConversion("3785.411784", "Litres", "3.785411784", litres);

        // aliases resolve to the same branch (gal → litre)
        UnitConversionService.UnitNormalization alias =
                conversions.normalize(new BigDecimal("1000"), "gal", "litre");
        assertConversion("3785.411784", "Litres", "3.785411784", alias);

        // reverse direction: value × (1 / 3.785411784) at 28 significant digits —
        // round-tripping back through the forward constant must land on 1000
        UnitConversionService.UnitNormalization gallons =
                conversions.normalize(new BigDecimal("1000"), "Litres", "Gallons");
        assertEquals("Gallons", gallons.normalizedUnit());
        BigDecimal roundTrip = gallons.normalizedQuantity()
                .multiply(new BigDecimal("3.785411784"));
        assertTrue(roundTrip.subtract(new BigDecimal("1000")).abs()
                        .compareTo(new BigDecimal("1e-18")) < 0,
                "round trip drifted: " + roundTrip);
        // the recorded factor is the reciprocal to within double-rounding
        assertTrue(gallons.conversionFactor()
                        .multiply(new BigDecimal("3.785411784"))
                        .subtract(BigDecimal.ONE).abs()
                        .compareTo(new BigDecimal("1e-24")) < 0,
                "conversion factor is not the reciprocal: " + gallons.conversionFactor());
    }

    @Test
    void massConversionsUseMetricTonnes() {
        assertConversion("5", "Metric Tonnes", "0.001",
                conversions.normalize(new BigDecimal("5000"), "kg", "Tonnes"));
        assertConversion("5000", "KG", "1000",
                conversions.normalize(new BigDecimal("5"), "Tonnes", "kg"));
        assertConversion("0.001", "Metric Tonnes", "0.001",
                conversions.normalize(new BigDecimal("1"), "KILOGRAMS", "t"));
    }

    @Test
    void unsupportedPairsAreRejectedWithTheExactContract() {
        UnitConversionService.UnsupportedUnitConversionException ex =
                assertThrows(UnitConversionService.UnsupportedUnitConversionException.class,
                        () -> conversions.normalize(BigDecimal.ONE, "Widgets", "kWh"));
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        assertEquals("Unit conversion from Widgets to kWh is not supported.",
                ex.getMessage());

        assertThrows(UnitConversionService.UnsupportedUnitConversionException.class,
                () -> conversions.normalize(BigDecimal.ONE, "kWh", "Widgets"));
        assertThrows(UnitConversionService.UnsupportedUnitConversionException.class,
                () -> conversions.normalize(BigDecimal.ONE, "KG", "kWh"));
    }

    private static void assertConversion(String expectedQuantity, String expectedUnit,
                                         String expectedFactor,
                                         UnitConversionService.UnitNormalization norm) {
        assertEquals(0, norm.normalizedQuantity().compareTo(new BigDecimal(expectedQuantity)),
                "quantity: expected " + expectedQuantity + " but was "
                        + norm.normalizedQuantity());
        assertEquals(expectedUnit, norm.normalizedUnit());
        assertEquals(0, norm.conversionFactor().compareTo(new BigDecimal(expectedFactor)),
                "factor: expected " + expectedFactor + " but was "
                        + norm.conversionFactor());
    }
}
