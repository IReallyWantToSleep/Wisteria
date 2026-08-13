package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.api.SuperResolutionAPI;
import io.homo.superresolution.api.event.FrameGenerationRegisterEvent;
import io.homo.superresolution.api.registry.FrameGenerationDescription;
import io.homo.superresolution.api.registry.FrameGenerationExecutionModel;
import io.homo.superresolution.api.registry.FrameGenerationGroups;
import io.homo.superresolution.api.registry.FrameGenerationRegistry;
import io.homo.superresolution.api.registry.LowLatencyBinding;
import io.homo.superresolution.api.utils.Requirement;
import io.homo.superresolution.common.config.ConfigSpecType;
import io.homo.superresolution.common.config.special.SpecialConfigDescription;
import io.homo.superresolution.core.streamline.Streamline;
import net.minecraft.network.chat.Component;
import org.ireallywanttosleep.wisteria.Wisteria;

import java.util.Optional;

/**
 * Registers Wisteria's frame generation backends with Super Resolution.
 * <p>
 * SR posts {@link FrameGenerationRegisterEvent} from {@code onClientStarted}, after every
 * loader has run its mod initializers, so subscribing from Wisteria's client entry point
 * is early enough. The listener is added once; SR only posts the event when it builds its
 * own registry.
 * <p>
 * Both backends implement DLSS Frame Generation, so both join
 * {@link FrameGenerationGroups#DLSS_FG}: the user picks the algorithm, and SR's negotiator
 * picks whichever backend is usable, preferring Streamline.
 */
public final class WisteriaFrameGeneration {
    /** DLSS-G through the Streamline interposer, which presents the generated frames itself. */
    public static final String STREAMLINE_ID = "wisteria:streamline";
    /** Cross-platform raw NVNGX DLSS-G, the backend that makes frame generation work on Linux. */
    public static final String NGX_ID = "wisteria:ngx";

    private WisteriaFrameGeneration() {
    }

    private static SpecialConfigDescription<DlssFgBackend> dlssFgBackendOption() {
        return SpecialConfigDescription.of(
                        "dlss_fg_backend",
                        ConfigSpecType.ENUM,
                        DlssFgBackend.AUTO
                )
                .setName(Component.translatable("wisteria.screen.config.special.dlss_fg.backend.name"))
                .setTooltip(Component.translatable("wisteria.screen.config.special.dlss_fg.backend.tooltip"))
                .setClazz(DlssFgBackend.class)
                .setValueNameSupplier(backend ->
                        Optional.of(Component.translatable(backend.translationKey())))
                .setValueSupplier(DlssFgBackend::fromConfig)
                .setItemEnableRequirement(DlssFgBackend::isAvailable)
                .setSaveConsumer(DlssFgBackend::save)
                .setRequiresRestartGame(true);
    }

    public static void register() {
        SuperResolutionAPI.EVENT_BUS.addListener(FrameGenerationRegisterEvent.class, event -> {
            FrameGenerationRegistry.register(
                    FrameGenerationDescription.builder()
                            .id(NGX_ID)
                            .displayName(Component.literal("NVNGX"))
                            .group(FrameGenerationGroups.DLSS_FG)
                            .priority(200)
                            .executionModel(FrameGenerationExecutionModel.APPLICATION_MANAGED_ASYNC)
                            // Driving raw NVNGX underneath the Streamline interposer is
                            // untested, so the negotiator keeps the two apart.
                            .lowLatencyBinding(LowLatencyBinding.excludes(WisteriaLowLatency.REFLEX_STREAMLINE_ID))
                            .addOptionDescription(dlssFgBackendOption())
                            .providerFactory(NgxFrameGenerationBackend::new)
                            .build()
            );

            FrameGenerationRegistry.register(
                    FrameGenerationDescription.builder()
                            .id(STREAMLINE_ID)
                            .displayName(Component.literal("Streamline"))
                            .group(FrameGenerationGroups.DLSS_FG)
                            .priority(100)
                            .requirement(Requirement.nothing().isTrue(
                                    () -> Streamline.isSupportedPlatform()
                                            && Streamline.isNativeAvailable()
                                            && Streamline.isInitialized()
                            ))
                            // DLSS-G paces its presents off Reflex, and the interposer only
                            // sees the markers when Reflex also runs through Streamline.
                            .lowLatencyBinding(LowLatencyBinding.requires(WisteriaLowLatency.REFLEX_STREAMLINE_ID))
                            .addOptionDescription(dlssFgBackendOption())
                            .providerFactory(StreamlineFrameGenerationBackend::new)
                            .build()
            );
            Wisteria.LOGGER.info("Registered frame generation backends {} and {}", STREAMLINE_ID, NGX_ID);
        });
    }
}
