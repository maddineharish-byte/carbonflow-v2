package com.carbonflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 — reference reads: {@code GET /reference/gwp-sets} (Node gates it
 * behind authentication only), {@code GET /reference/emission-factors}
 * ({@code emission_factors.read}) and the greenfield
 * {@code GET /reference/methodologies} lookup (reference-only table).
 */
class ReferenceDataTest extends AccountingTestBase {

    @Test
    void gwpSetsNeedOnlyAuthenticationAndCarryTheSeededValues() throws Exception {
        // PLATFORM_ADMIN holds no domain permission — authentication alone suffices
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        Api api = getJson("/api/v1/reference/gwp-sets", platform);
        assertEquals(200, api.status(), api.body().toString());
        JsonNode sets = api.body().path("data");
        assertEquals(3, sets.size());

        JsonNode ar6 = setByCode(sets, "IPCC_AR6");
        assertNotNull(ar6, "seeded AR6 set missing: " + sets);
        assertTrue(ar6.path("isDefault").asBoolean());
        assertEquals("IPCC Sixth Assessment Report (AR6)", ar6.path("name").asText());
        assertEquals("AR6", ar6.path("assessmentReport").asText());
        assertEquals(2021, ar6.path("publicationYear").asInt());

        // per-gas values present and exact (GWP 100-year, stripped on the wire)
        JsonNode values = ar6.path("values");
        assertEquals(6, values.size());
        assertWire("1", requireValue(values, "CO2"), "gwp100yr");
        assertWire("27.9", requireValue(values, "CH4"), "gwp100yr");
        assertWire("273", requireValue(values, "N2O"), "gwp100yr");
        assertWire("28",
                requireValue(setByCode(sets, "IPCC_AR5").path("values"), "CH4"),
                "gwp100yr");
        assertWire("1",
                requireValue(setByCode(sets, "IPCC_AR4").path("values"), "CO2"),
                "gwp100yr");

        // no permission is required — but a principal is
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header.",
                getJson("/api/v1/reference/gwp-sets", null));
    }

    @Test
    void emissionFactorsExposeTheSeededActiveVersions() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        Api api = getJson("/api/v1/reference/emission-factors", token);
        assertEquals(200, api.status(), api.body().toString());
        JsonNode factors = api.body().path("data");
        assertEquals(7, factors.size(), "V2 seeds exactly seven factors");

        JsonNode naturalGas = null;
        for (JsonNode factor : factors) {
            if ("NATURAL_GAS".equals(factor.path("activityType").asText())) {
                naturalGas = factor;
            }
        }
        assertNotNull(naturalGas, "NATURAL_GAS factor missing");
        assertEquals("SCOPE_1", naturalGas.path("scope").asText());
        assertEquals("STATIONARY_COMBUSTION", naturalGas.path("category").asText());
        assertEquals("kWh", naturalGas.path("inputUnit").asText());
        assertEquals("Natural Gas (Pipeline)", naturalGas.path("fuelOrActivity").asText());

        JsonNode version = naturalGas.path("versions").get(0);
        assertEquals(1, version.path("versionNumber").asInt());
        assertWire("0.18288", version, "co2eFactor");
        assertWire("0.18254", version, "co2Factor");
        assertWire("0.00024", version, "ch4Factor");
        assertWire("0.0001", version, "n2oFactor");
        assertEquals("kgCO2e/kWh", version.path("factorUnit").asText());
        assertEquals("UK DEFRA / BEIS", version.path("source").asText());
        assertEquals(2024, version.path("sourceYear").asInt());
        assertEquals("ACTIVE", version.path("status").asText());
        assertEquals("2024-01-01", version.path("effectiveStart").asText());
        assertTrue(version.path("effectiveEnd").isMissingNode()
                        || version.path("effectiveEnd").isNull(),
                "open-ended active version must not carry effectiveEnd");

        // market-based composite factor exists with its zero gas split
        JsonNode green = null;
        for (JsonNode factor : factors) {
            if ("GREEN_POWER_TARIFF".equals(factor.path("activityType").asText())) {
                green = factor;
            }
        }
        assertNotNull(green);
        assertEquals("SCOPE_2", green.path("scope").asText());
        assertEquals("ELECTRICITY_MARKET", green.path("category").asText());
        assertWire("0", green.path("versions").get(0), "co2eFactor");

        // permission: every seeded role may read reference factors; anon may not
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        assertEquals(200,
                getJson("/api/v1/reference/emission-factors", platform).status());
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header.",
                getJson("/api/v1/reference/emission-factors", null));
    }

    @Test
    void methodologiesAreReferenceOnlyLookups() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        Api api = getJson("/api/v1/reference/methodologies", token);
        assertEquals(200, api.status(), api.body().toString());
        JsonNode rows = api.body().path("data");
        assertEquals(2, rows.size(), "V2 seeds exactly two methodologies");

        List<String> codes = new ArrayList<>();
        for (JsonNode row : rows) {
            codes.add(row.path("code").asText());
            assertFalse(row.path("id").asText().isBlank());
            assertFalse(row.path("name").asText().isBlank());
            assertFalse(row.path("version").asText().isBlank());
        }
        assertTrue(codes.contains("GHG_PROTOCOL_CORP"), codes.toString());
        assertTrue(codes.contains("ISO_14064_1"), codes.toString());

        // greenfield lookup shares the emission_factors.read gate
        String platform = loginToken(PLATFORM_EMAIL, PASSWORD);
        assertEquals(200, getJson("/api/v1/reference/methodologies", platform).status());
        assertEnvelope(401, "UNAUTHORIZED",
                "Missing or malformed Authorization header.",
                getJson("/api/v1/reference/methodologies", null));
    }

    private static JsonNode setByCode(JsonNode sets, String code) {
        JsonNode found = null;
        for (JsonNode set : sets) {
            if (code.equals(set.path("code").asText())) {
                found = set;
            }
        }
        assertNotNull(found, "GWP set missing: " + code);
        return found;
    }

    private static JsonNode requireValue(JsonNode values, String gas) {
        JsonNode found = null;
        for (JsonNode value : values) {
            if (gas.equals(value.path("gas").asText())) {
                found = value;
            }
        }
        assertNotNull(found, "GWP value missing for gas: " + gas);
        return found;
    }
}
