package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.event.FrameGenerationRegisterEvent;
import io.homo.superresolution.api.registry.FrameGenerationDescription;
import io.homo.superresolution.api.registry.FrameGenerationRegistry;
import org.ireallywanttosleep.wisteria.Wisteria;

/**
 * Registers Wisteria's frame generation backends with Super Resolution.
 * <p>
 * SR posts {@link FrameGenerationRegisterEvent} from {@code onClientStarted}, after every
 * loader has run its mod initializers, so subscribing from Wisteria's client entry point
 * is early enough. The listener is added once; SR only posts the event when it builds its
 * own registry.
 */
public final class WisteriaFrameGeneration {
    /** Cross-platform raw NVNGX DLSS-G, the backend that makes frame generation work on Linux. */
    public static final String NGX_ID = "wisteria:ngx";

    private WisteriaFrameGeneration() {
    }

    public static void register() {
        SuperResolutionAPI.EVENT_BUS.addListener(FrameGenerationRegisterEvent.class, event -> {
            FrameGenerationRegistry.register(
                    FrameGenerationDescription.builder()
                            .id(NGX_ID)
                            .displayName("NVNGX")
                            .providerFactory(NgxFrameGenerationBackend::new)
                            .build()
            );
            Wisteria.LOGGER.info("Registered frame generation backend {}", NGX_ID);
        });
    }
}
