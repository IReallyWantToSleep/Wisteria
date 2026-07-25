package org.ireallywanttosleep.wisteria;

import org.ireallywanttosleep.wisteria.backend.WisteriaFrameGeneration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared entry point. Wisteria is the frame-generation companion to Super Resolution: it
 * owns the DLSS-G backends and registers them with SR's provider APIs, while SR keeps the
 * presentation pipeline (swapchain, present pacing, present-id/Reflex coupling).
 * <p>
 * The integration is API-only — Wisteria deliberately declares no mixins. Everything it
 * needs is reached through SR's {@code FrameGeneration} / {@code LowLatency} registries,
 * so SR's internals stay free to change.
 */
public final class Wisteria {
    public static final String MOD_ID = "wisteria";
    public static final Logger LOGGER = LoggerFactory.getLogger("Wisteria");

    private static boolean initialized;

    private Wisteria() {
    }

    /**
     * Called once from each loader's client entry point. Registration must happen before
     * SR initializes frame generation, which it does after the graphics backend comes up.
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("Wisteria initializing");
        WisteriaFrameGeneration.register();
    }
}
