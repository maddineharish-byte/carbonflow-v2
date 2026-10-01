package com.carbonflow.recovery;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;

/**
 * Strict ISO-8601 {@link Instant} deserialiser for recovery manifests.
 *
 * <p>Jackson's stock {@code InstantDeserializer} accepts a bare epoch number as
 * well as an ISO string. That leniency is a genuine hazard here: a recovery
 * boundary is the one timestamp an entire recovery decision hinges on, and
 * {@code 1750000000} is interpretable as seconds or milliseconds depending on
 * configuration. A document that parses under two readings is worse than one
 * that refuses to parse.
 *
 * <p>Only a quoted ISO-8601 instant is accepted. The value must carry an
 * explicit offset or {@code Z}; a local date-time without a zone is rejected
 * because the manifest format stores UTC instants exclusively.
 */
final class StrictInstantDeserializer extends JsonDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {

        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            // Numbers, booleans and objects are all refused with the same
            // message, so an operator is told the required format rather than
            // being handed a silent coercion.
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }

        String raw = parser.getText();
        try {
            TemporalAccessor parsed = java.time.format.DateTimeFormatter.ISO_DATE_TIME
                    .parse(raw);
            if (!parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
                throw new DateTimeParseException(
                        "timestamp must carry an explicit offset or Z", raw, 0);
            }
            return Instant.from(parsed);
        } catch (java.time.DateTimeException e) {
            throw new IOException("Recovery manifest timestamp is not a valid "
                    + "ISO-8601 instant with an explicit offset: '" + raw + "'", e);
        }
    }
}