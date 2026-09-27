package com.carbonflow.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Writes a {@link BigDecimal} the way the Node reference writes a JavaScript
 * number: trailing zeros stripped, never exponent notation.
 *
 * <p>Node converts every numeric column through {@code Number(...)} before
 * serializing, so {@code 100.0000} leaves the wire as {@code 100},
 * {@code 0.18288000} as {@code 0.18288} and a four-decimal total of zero as
 * {@code 0}. A plain Jackson {@code BigDecimal} writer would emit the stored
 * scale instead ({@code 100.0000}), which changes the literal a client
 * compares against without changing the value. Stripping on the way out keeps
 * the JSON byte-identical to the reference for every Phase 6 numeric field
 * while the persisted column keeps its schema scale (NUMERIC precision is a
 * storage property, not a wire property).
 *
 * <p>{@code stripTrailingZeros()} alone would render large values as
 * {@code 4E+1}; {@code toPlainString()} fixes that to {@code 40}, matching
 * JavaScript's number-to-string rules for the magnitudes this domain sees.
 */
public class PlainBigDecimalSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator generator,
                          SerializerProvider serializers) throws IOException {
        generator.writeNumber(value.stripTrailingZeros().toPlainString());
    }
}
