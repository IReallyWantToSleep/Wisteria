package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.common.config.SuperResolutionConfig;
import io.homo.superresolution.common.framegeneration.FrameGenerationDescriptions;
import io.homo.superresolution.core.streamline.Streamline;

/**
 * Concrete backend preference exposed by Wisteria for the DLSS-FG algorithm group.
 */
public enum DlssFgBackend {
    AUTO(
            FrameGenerationDescriptions.AUTO_ID,
            "wisteria.screen.config.special.dlss_fg.backend.auto"
    ),
    STREAMLINE(
            WisteriaFrameGeneration.STREAMLINE_ID,
            "wisteria.screen.config.special.dlss_fg.backend.streamline"
    ),
    NVNGX(
            WisteriaFrameGeneration.NGX_ID,
            "wisteria.screen.config.special.dlss_fg.backend.ngx"
    );

    private final String configId;
    private final String translationKey;

    DlssFgBackend(String configId, String translationKey) {
        this.configId = configId;
        this.translationKey = translationKey;
    }

    public String configId() {
        return configId;
    }

    public String translationKey() {
        return translationKey;
    }

    public boolean isAvailable() {
        return this != STREAMLINE
                || (Streamline.isSupportedPlatform() && Streamline.isNativeAvailable());
    }

    public static DlssFgBackend fromConfig() {
        String configured = SuperResolutionConfig.getFrameGenerationBackend();
        for (DlssFgBackend backend : values()) {
            if (backend.configId.equals(configured)) {
                return backend;
            }
        }
        return AUTO;
    }

    public static void save(DlssFgBackend backend) {
        SuperResolutionConfig.setFrameGenerationBackend(
                backend == null ? AUTO.configId : backend.configId
        );
    }
}
