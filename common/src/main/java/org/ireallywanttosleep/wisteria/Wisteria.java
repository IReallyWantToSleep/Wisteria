package org.ireallywanttosleep.wisteria;

import io.homo.superresolution.api.StreamlineDistribution;
import org.ireallywanttosleep.wisteria.backend.WisteriaFrameGeneration;
import org.ireallywanttosleep.wisteria.backend.WisteriaLowLatency;
import org.ireallywanttosleep.wisteria.natives.StreamlineNativeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Shared entry point. Wisteria is the frame-generation companion to Super Resolution: it
 * owns the DLSS-G backends, the Streamline-based Reflex backend and the Streamline runtime
 * itself, and registers them with SR's provider APIs, while SR keeps the presentation
 * pipeline (swapchain, present pacing, present-id/Reflex coupling).
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
     * The game directory comes from the loader because the shared module has no loader API;
     * it is where the Streamline runtime gets unpacked.
     */
    public static synchronized void init(Path gameDirectory) {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.info("Wisteria initializing");
        // Extraction itself is deferred: SR only asks for the directory if it decides to
        // load Streamline, so builds without the SDK never touch the disk.
        Path streamlineDirectory = gameDirectory.resolve("config").resolve(MOD_ID).resolve("streamline");
        StreamlineDistribution.provide(() -> StreamlineNativeExtractor.extract(streamlineDirectory));
        WisteriaFrameGeneration.register();
        WisteriaLowLatency.register();
    }
}
