package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.event.LowLatencyRegisterEvent;
import io.homo.superresolution.api.registry.LowLatencyDescription;
import io.homo.superresolution.api.registry.LowLatencyGroups;
import io.homo.superresolution.api.registry.LowLatencyRegistry;
import io.homo.superresolution.api.utils.Requirement;
import io.homo.superresolution.core.streamline.Streamline;
import net.minecraft.network.chat.Component;
import org.ireallywanttosleep.wisteria.Wisteria;

/**
 * Registers Wisteria's Reflex backend with Super Resolution.
 * <p>
 * SR already ships a Reflex backend on top of VK_NV_low_latency2 at priority 100. This one
 * drives Reflex through Streamline's sl.reflex / sl.pcl plugins instead and takes priority,
 * because it is the only variant the Streamline DLSS-G interposer can see markers from.
 */
public final class WisteriaLowLatency {
    /** Reflex through the Streamline interposer, the pacing source Streamline DLSS-G needs. */
    public static final String REFLEX_STREAMLINE_ID = "wisteria:reflex_streamline";

    private WisteriaLowLatency() {
    }

    public static void register() {
        SuperResolutionAPI.EVENT_BUS.addListener(LowLatencyRegisterEvent.class, event -> {
            LowLatencyRegistry.register(
                    LowLatencyDescription.builder()
                            .id(REFLEX_STREAMLINE_ID)
                            .displayName(Component.literal("Streamline Reflex"))
                            .group(LowLatencyGroups.NV_REFLEX)
                            .priority(200)
                            .requirement(Requirement.nothing().isTrue(
                                    () -> Streamline.isSupportedPlatform()
                                            && Streamline.isNativeAvailable()
                                            && Streamline.isInitialized()
                            ))
                            .providerFactory(StreamlineReflexProvider::new)
                            .build()
            );
            Wisteria.LOGGER.info("Registered low latency backend {}", REFLEX_STREAMLINE_ID);
        });
    }
}
