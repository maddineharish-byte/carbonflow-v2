package com.carbonflow.recovery;

import com.fasterxml.jackson.databind.module.SimpleModule;

import java.time.Instant;

/**
 * Registers {@link StrictInstantDeserializer} for {@link Instant}.
 *
 * <p>Applied to every {@link RecoveryManifestWriter} mapper. Registering on the
 * type rather than on individual record components means the strictness rule
 * cannot be forgotten on a field added later — the exact failure mode a
 * recovery manifest must not have, where one timestamp silently accepts a
 * different format from all the others.
 */
final class RecoveryManifestTimeModule extends SimpleModule {

    RecoveryManifestTimeModule() {
        super("carbonflow-recovery-time");
        addDeserializer(Instant.class, new StrictInstantDeserializer());
    }
}