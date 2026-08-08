/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Moved from Super Resolution (io.homo.superresolution.common.framegeneration)
 * when frame generation was split into this mod; original copyright retained.
 */

package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.common.framegeneration.FrameGenerationMode;
import io.homo.superresolution.common.framegeneration.constants.FGConstants;
import io.homo.superresolution.common.presentation.capture.FrameResources;
import io.homo.superresolution.core.RenderSystems;
import io.homo.superresolution.core.graphics.impl.texture.TextureDescription;
import io.homo.superresolution.core.graphics.impl.texture.TextureType;
import io.homo.superresolution.core.graphics.impl.texture.TextureUsages;
import io.homo.superresolution.core.graphics.vulkan.VulkanCommandBuffer;
import io.homo.superresolution.core.graphics.vulkan.VulkanDevice;
import io.homo.superresolution.core.graphics.vulkan.VulkanTexture;
import io.homo.superresolution.core.ngx.NgxConstants;
import io.homo.superresolution.core.ngx.NgxDLSSFGCreateParams;
import io.homo.superresolution.core.ngx.NgxDLSSFGOptEvalParams;
import io.homo.superresolution.core.ngx.NgxFeature;
import io.homo.superresolution.core.ngx.NgxImageSubresourceRange;
import io.homo.superresolution.core.ngx.NgxInitializer;
import io.homo.superresolution.core.ngx.NgxParameters;
import io.homo.superresolution.core.ngx.NgxResourceVK;
import io.homo.superresolution.core.ngx.NgxVKDLSSFGEvalParams;
import io.homo.superresolution.core.ngx.NgxVulkan;
import org.ireallywanttosleep.wisteria.Wisteria;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Synchronous raw NVNGX DLSS-G adapter aligned with Wisteria 1.21.1.
 *
 * <p>Feature creation is recorded into a dedicated command buffer, submitted, and
 * fence-completed before the feature is published. Per-frame evaluation is then
 * recorded into SR's synchronous presentation command buffer and returned as the
 * capability-driven generated outputs plus the NGX real output.</p>
 */
public final class NgxFrameGenerationAdapter {
    private static final int MIN_WIDTH_OR_HEIGHT = 128;
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private static boolean supportQueried;
    private static int maxGeneratedFrameCount;
    private static NgxParameters ngxParameters;
    private static NgxFeature ngxFeature;
    private static FeatureKey featureKey;
    private static final List<VulkanTexture> interpolatedTextures = new ArrayList<>();
    private static VulkanTexture realOutputTexture;
    private static final NgxDLSSFGOptEvalParams OPT_EVAL_PARAMS =
            new NgxDLSSFGOptEvalParams();
    private static FloatBuffer cameraViewToClip;
    private static FloatBuffer clipToCameraView;
    private static FloatBuffer clipToLensClip;
    private static FloatBuffer clipToPrevClip;
    private static FloatBuffer prevClipToClip;

    private NgxFrameGenerationAdapter() {
    }

    public static synchronized void initialize() {
        supportQueried = false;
        maxGeneratedFrameCount = 0;
        REPORTED_FAILURES.clear();
    }

    public static synchronized void shutdown() {
        releaseFeature(true);
        freeMatrixBuffers();
        supportQueried = false;
        maxGeneratedFrameCount = 0;
        REPORTED_FAILURES.clear();
    }

    public static synchronized boolean isAvailable() {
        if (!supportQueried) {
            refreshSupport();
        }
        return maxGeneratedFrameCount > 0;
    }

    public static synchronized int supportedGeneratedFrameCount() {
        if (!supportQueried) {
            refreshSupport();
        }
        return maxGeneratedFrameCount;
    }

    public static int minimumWidthOrHeight() {
        return MIN_WIDTH_OR_HEIGHT;
    }

    /**
     * Records DLSS-G evaluation in the synchronous presentation command buffer.
     * The returned generated frames are presented before the returned real frame.
     */
    public static synchronized PrepareResult prepareFrame(
            FrameResources frameResources,
            FGConstants constants,
            FrameGenerationMode mode,
            int colorWidth,
            int colorHeight,
            long commandBuffer
    ) {
        VulkanDevice device = RenderSystems.vulkan() == null
                ? null
                : RenderSystems.vulkan().device();
        VulkanTexture backbuffer = frameResources == null
                ? null
                : frameResources.finalColorVulkanTexture();
        VulkanTexture hudless = frameResources == null
                ? null
                : frameResources.hudlessColorVulkanTexture();
        VulkanTexture depth = frameResources == null
                ? null
                : frameResources.depthVulkanTexture();
        VulkanTexture motionVectors = frameResources == null
                ? null
                : frameResources.motionVectorVulkanTexture();
        if (!validInputs(
                device,
                frameResources,
                constants,
                mode,
                colorWidth,
                colorHeight,
                commandBuffer,
                backbuffer,
                hudless,
                depth,
                motionVectors
        )) {
            return null;
        }

        int generatedFrameCount = Math.min(
                Math.max(1, mode.generatedFrameCount()),
                supportedGeneratedFrameCount()
        );
        VulkanTexture realOutput;
        try {
            ensureFeature(device, backbuffer, depth);
            ensureInterpolatedTextures(device, generatedFrameCount, backbuffer);
            realOutput = ensureRealOutputTexture(device, backbuffer);
        } catch (RuntimeException | Error throwable) {
            reportFailureOnce(
                    "feature-create",
                    "Failed to create the NGX DLSS-G feature",
                    throwable
            );
            releaseFeature(false);
            return null;
        }

        recordTransitionsToGeneral(
                device,
                commandBuffer,
                backbuffer,
                hudless,
                depth,
                motionVectors,
                generatedFrameCount,
                realOutput
        );
        fillOptEvalParams(constants, generatedFrameCount);

        for (int frameIndex = 1; frameIndex <= generatedFrameCount; frameIndex++) {
            OPT_EVAL_PARAMS.multiFrameCount = generatedFrameCount;
            OPT_EVAL_PARAMS.multiFrameIndex = frameIndex;
            VulkanTexture generatedOutput = interpolatedTextures.get(frameIndex - 1);
            int result;
            try (
                    NgxResourceVK backbufferResource = createResource(backbuffer, false);
                    NgxResourceVK depthResource = createResource(depth, false);
                    NgxResourceVK motionVectorsResource = createResource(motionVectors, false);
                    NgxResourceVK hudlessResource = createResource(hudless, false);
                    NgxResourceVK outputResource = createResource(generatedOutput, true);
                    NgxResourceVK realOutputResource = createResource(realOutput, true)
            ) {
                NgxVKDLSSFGEvalParams evalParams = new NgxVKDLSSFGEvalParams();
                evalParams.backbuffer = backbufferResource;
                evalParams.depth = depthResource;
                evalParams.motionVectors = motionVectorsResource;
                evalParams.hudless = hudlessResource;
                evalParams.outputInterpolatedFrame = outputResource;
                evalParams.outputRealFrame = realOutputResource;
                result = NgxVulkan.evaluateDLSSFG(
                        commandBuffer,
                        ngxFeature,
                        ngxParameters,
                        evalParams,
                        OPT_EVAL_PARAMS
                );
            }
            if (!NgxConstants.succeeded(result)) {
                reportFailureOnce(
                        "evaluate-" + result,
                        "NGX DLSS-G evaluation failed for logical frame "
                                + frameResources.logicalFrameIndex()
                                + ", generated frame " + frameIndex
                                + "/" + generatedFrameCount
                                + " with result=" + result,
                        null
                );
                return null;
            }
            generatedOutput.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
        }

        realOutput.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
        return new PrepareResult(
                List.copyOf(interpolatedTextures.subList(0, generatedFrameCount)),
                realOutput
        );
    }

    public static synchronized void disable() {
        // Keep the resident feature so menu/screenshot transitions do not recreate it.
    }

    private static boolean validInputs(
            VulkanDevice device,
            FrameResources frameResources,
            FGConstants constants,
            FrameGenerationMode mode,
            int colorWidth,
            int colorHeight,
            long commandBuffer,
            VulkanTexture backbuffer,
            VulkanTexture hudless,
            VulkanTexture depth,
            VulkanTexture motionVectors
    ) {
        if (!isAvailable()
                || device == null
                || frameResources == null
                || constants == null
                || mode == null
                || !mode.isEnabled()
                || commandBuffer == 0L
                || backbuffer == null
                || hudless == null
                || depth == null
                || motionVectors == null) {
            return false;
        }
        if (backbuffer.getDevice() != device
                || hudless.getDevice() != device
                || depth.getDevice() != device
                || motionVectors.getDevice() != device) {
            return false;
        }
        if (backbuffer.getWidth() != colorWidth
                || backbuffer.getHeight() != colorHeight
                || hudless.getWidth() != colorWidth
                || hudless.getHeight() != colorHeight
                || hudless.getTextureFormat() != backbuffer.getTextureFormat()) {
            return false;
        }
        return depth.getWidth() == motionVectors.getWidth()
                && depth.getHeight() == motionVectors.getHeight()
                && colorWidth >= MIN_WIDTH_OR_HEIGHT
                && colorHeight >= MIN_WIDTH_OR_HEIGHT;
    }

    private static synchronized void refreshSupport() {
        supportQueried = true;
        maxGeneratedFrameCount = 0;
        if (!NgxInitializer.initializeIfSupported()) {
            return;
        }

        NgxParameters capabilities = new NgxParameters();
        try {
            int capabilitiesResult = NgxVulkan.getCapabilityParameters(capabilities);
            if (!NgxConstants.succeeded(capabilitiesResult)) {
                reportFailureOnce(
                        "capability-" + capabilitiesResult,
                        "NVSDK_NGX_VULKAN_GetCapabilityParameters failed with result="
                                + capabilitiesResult,
                        null
                );
                return;
            }

            int[] available = new int[1];
            int availableResult = capabilities.getInt(
                    NgxConstants.FRAME_GENERATION_AVAILABLE,
                    available
            );
            if (!NgxConstants.succeeded(availableResult) || available[0] == 0) {
                logUnavailableReason(capabilities);
                return;
            }

            long[] multiFrameCountMax = new long[1];
            int multiFrameResult = capabilities.getUnsignedInt(
                    NgxConstants.DLSSFG_MULTI_FRAME_COUNT_MAX,
                    multiFrameCountMax
            );
            maxGeneratedFrameCount = NgxConstants.succeeded(multiFrameResult)
                    && multiFrameCountMax[0] > 1
                    ? (int) multiFrameCountMax[0]
                    : 1;
            Wisteria.LOGGER.info(
                    "NGX DLSS-G is available, maximum generated frames per rendered frame: {}",
                    maxGeneratedFrameCount
            );
        } finally {
            capabilities.destroy();
        }
    }

    private static void logUnavailableReason(NgxParameters capabilities) {
        int[] initResult = new int[1];
        int[] needsDriver = new int[1];
        long[] driverMajor = new long[1];
        long[] driverMinor = new long[1];
        capabilities.getInt(NgxConstants.FRAME_GENERATION_FEATURE_INIT_RESULT, initResult);
        capabilities.getInt(NgxConstants.FRAME_GENERATION_NEEDS_UPDATED_DRIVER, needsDriver);
        capabilities.getUnsignedInt(
                NgxConstants.FRAME_GENERATION_MIN_DRIVER_VERSION_MAJOR,
                driverMajor
        );
        capabilities.getUnsignedInt(
                NgxConstants.FRAME_GENERATION_MIN_DRIVER_VERSION_MINOR,
                driverMinor
        );
        reportFailureOnce(
                "unavailable",
                "NGX DLSS-G is not available on this system. FeatureInitResult=" + initResult[0]
                        + ", needsUpdatedDriver=" + needsDriver[0]
                        + ", minimum driver=" + driverMajor[0] + "." + driverMinor[0],
                null
        );
    }

    private static void ensureFeature(
            VulkanDevice device,
            VulkanTexture backbuffer,
            VulkanTexture depth
    ) {
        FeatureKey desired = new FeatureKey(
                backbuffer.getWidth(),
                backbuffer.getHeight(),
                backbuffer.getTextureFormat().vk(),
                depth.getWidth(),
                depth.getHeight()
        );
        if (ngxFeature != null && ngxFeature.isValid() && desired.equals(featureKey)) {
            return;
        }
        releaseFeature(false);

        NgxParameters parameters = new NgxParameters();
        NgxFeature feature = new NgxFeature();
        VulkanCommandBuffer createCommandBuffer = device.createCommandBuffer();
        try {
            int parametersResult = NgxVulkan.getCapabilityParameters(parameters);
            requireNgxSuccess(
                    "NVSDK_NGX_VULKAN_GetCapabilityParameters",
                    parametersResult
            );

            NgxDLSSFGCreateParams createParams = new NgxDLSSFGCreateParams();
            createParams.width = desired.colorWidth();
            createParams.height = desired.colorHeight();
            createParams.nativeBackbufferFormat = desired.backbufferFormat();
            createParams.renderWidth = desired.renderWidth();
            createParams.renderHeight = desired.renderHeight();
            createParams.dynamicResolutionScaling = false;

            createCommandBuffer.begin();
            int createResult = NgxVulkan.createDLSSFG(
                    createCommandBuffer.getNativeCommandBuffer().address(),
                    1,
                    1,
                    feature,
                    parameters,
                    createParams
            );
            createCommandBuffer.end();
            requireNgxSuccess("NGX_VK_CREATE_DLSSG", createResult);

            device.submitCommandBuffer(createCommandBuffer);
            createCommandBuffer.waitForFence();

            ngxParameters = parameters;
            ngxFeature = feature;
            featureKey = desired;
            Wisteria.LOGGER.info(
                    "Created synchronous NGX DLSS-G feature: color {}x{} "
                            + "(VkFormat {}), render {}x{}",
                    desired.colorWidth(),
                    desired.colorHeight(),
                    desired.backbufferFormat(),
                    desired.renderWidth(),
                    desired.renderHeight()
            );
        } catch (RuntimeException | Error throwable) {
            feature.close();
            parameters.close();
            throw throwable;
        } finally {
            createCommandBuffer.destroy();
        }
    }

    private static synchronized void releaseFeature(boolean quiet) {
        if (ngxFeature == null
                && ngxParameters == null
                && interpolatedTextures.isEmpty()
                && realOutputTexture == null) {
            featureKey = null;
            return;
        }
        try {
            VulkanDevice device = RenderSystems.vulkan() == null
                    ? null
                    : RenderSystems.vulkan().device();
            if (device != null) {
                device.getMainQueue().waitIdle();
            }
        } catch (Throwable throwable) {
            if (!quiet) {
                Wisteria.LOGGER.warn(
                        "Failed to drain the queue before releasing DLSS-G",
                        throwable
                );
            }
        }

        if (ngxFeature != null) {
            int result = ngxFeature.release();
            if (!NgxConstants.succeeded(result) && !quiet) {
                Wisteria.LOGGER.warn(
                        "Failed to release the NGX DLSS-G feature. Result: {}",
                        result
                );
            }
            ngxFeature = null;
        }
        if (ngxParameters != null) {
            int result = ngxParameters.destroy();
            if (!NgxConstants.succeeded(result) && !quiet) {
                Wisteria.LOGGER.warn(
                        "Failed to destroy the NGX DLSS-G parameters. Result: {}",
                        result
                );
            }
            ngxParameters = null;
        }
        for (VulkanTexture texture : interpolatedTextures) {
            texture.destroy();
        }
        interpolatedTextures.clear();
        if (realOutputTexture != null) {
            realOutputTexture.destroy();
            realOutputTexture = null;
        }
        featureKey = null;
    }

    private static void ensureInterpolatedTextures(
            VulkanDevice device,
            int count,
            VulkanTexture backbuffer
    ) {
        boolean matches = interpolatedTextures.size() >= count;
        for (int index = 0; matches && index < count; index++) {
            matches = matchesOutput(interpolatedTextures.get(index), backbuffer);
        }
        if (matches) {
            return;
        }
        device.getMainQueue().waitIdle();
        for (VulkanTexture texture : interpolatedTextures) {
            texture.destroy();
        }
        interpolatedTextures.clear();
        for (int index = 0; index < count; index++) {
            interpolatedTextures.add(createOutputTexture(
                    device,
                    backbuffer,
                    "SRDlssGInterpolated-" + index
            ));
        }
    }

    private static VulkanTexture ensureRealOutputTexture(
            VulkanDevice device,
            VulkanTexture backbuffer
    ) {
        if (realOutputTexture != null && matchesOutput(realOutputTexture, backbuffer)) {
            return realOutputTexture;
        }
        device.getMainQueue().waitIdle();
        if (realOutputTexture != null) {
            realOutputTexture.destroy();
        }
        realOutputTexture = createOutputTexture(device, backbuffer, "SRDlssGOutputReal");
        return realOutputTexture;
    }

    private static boolean matchesOutput(VulkanTexture output, VulkanTexture template) {
        return output.getDevice() == template.getDevice()
                && output.getWidth() == template.getWidth()
                && output.getHeight() == template.getHeight()
                && output.getTextureFormat() == template.getTextureFormat();
    }

    private static VulkanTexture createOutputTexture(
            VulkanDevice device,
            VulkanTexture template,
            String label
    ) {
        TextureDescription description = TextureDescription.create()
                .type(TextureType.Texture2D)
                .format(template.getTextureFormat())
                .size(template.getWidth(), template.getHeight())
                .usages(TextureUsages.create()
                        .sampler()
                        .storage()
                        .transferSource()
                        .transferDestination())
                .label(label)
                .build();
        return (VulkanTexture) device.createTexture(description);
    }

    private static void recordTransitionsToGeneral(
            VulkanDevice device,
            long commandBuffer,
            VulkanTexture backbuffer,
            VulkanTexture hudless,
            VulkanTexture depth,
            VulkanTexture motionVectors,
            int generatedFrameCount,
            VulkanTexture realOutput
    ) {
        List<VulkanTexture> textures = new ArrayList<>();
        Set<VulkanTexture> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        addIfNotGeneral(textures, seen, backbuffer);
        addIfNotGeneral(textures, seen, hudless);
        addIfNotGeneral(textures, seen, depth);
        addIfNotGeneral(textures, seen, motionVectors);
        for (int index = 0; index < generatedFrameCount; index++) {
            addIfNotGeneral(textures, seen, interpolatedTextures.get(index));
        }
        addIfNotGeneral(textures, seen, realOutput);
        if (textures.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(
                    textures.size(),
                    stack
            );
            for (int index = 0; index < textures.size(); index++) {
                VulkanTexture texture = textures.get(index);
                int oldLayout = texture.getCurrentLayout();
                barriers.get(index)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                                ? 0
                                : VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                        .oldLayout(oldLayout)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(texture.handle())
                        .subresourceRange(range -> range
                                .aspectMask(texture.getAspectMask())
                                .baseMipLevel(0)
                                .levelCount(texture.getMipmapSettings().getLevels())
                                .baseArrayLayer(0)
                                .layerCount(1));
            }
            vkCmdPipelineBarrier(
                    new VkCommandBuffer(commandBuffer, device.getVkDevice()),
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0,
                    null,
                    null,
                    barriers
            );
        }

        for (VulkanTexture texture : textures) {
            texture.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
        }
    }

    private static void addIfNotGeneral(
            List<VulkanTexture> textures,
            Set<VulkanTexture> seen,
            VulkanTexture texture
    ) {
        if (seen.add(texture) && texture.getCurrentLayout() != VK_IMAGE_LAYOUT_GENERAL) {
            textures.add(texture);
        }
    }

    private static void fillOptEvalParams(FGConstants constants, int generatedFrameCount) {
        ensureMatrixBuffers();
        putMatrix(cameraViewToClip, constants.cameraViewToClip());
        putMatrix(clipToCameraView, constants.clipToCameraView());
        putMatrix(clipToLensClip, constants.clipToLensClip());
        putMatrix(clipToPrevClip, constants.clipToPrevClip());
        putMatrix(prevClipToClip, constants.prevClipToClip());
        OPT_EVAL_PARAMS.cameraViewToClip = cameraViewToClip;
        OPT_EVAL_PARAMS.clipToCameraView = clipToCameraView;
        OPT_EVAL_PARAMS.clipToLensClip = clipToLensClip;
        OPT_EVAL_PARAMS.clipToPrevClip = clipToPrevClip;
        OPT_EVAL_PARAMS.prevClipToClip = prevClipToClip;
        OPT_EVAL_PARAMS.jitterOffset[0] = constants.jitterOffsetX();
        OPT_EVAL_PARAMS.jitterOffset[1] = constants.jitterOffsetY();
        OPT_EVAL_PARAMS.motionVectorScale[0] = constants.motionVectorScaleX();
        OPT_EVAL_PARAMS.motionVectorScale[1] = constants.motionVectorScaleY();
        OPT_EVAL_PARAMS.cameraPinholeOffset[0] = constants.cameraPinholeOffsetX();
        OPT_EVAL_PARAMS.cameraPinholeOffset[1] = constants.cameraPinholeOffsetY();
        OPT_EVAL_PARAMS.cameraPosition[0] = constants.cameraPosX();
        OPT_EVAL_PARAMS.cameraPosition[1] = constants.cameraPosY();
        OPT_EVAL_PARAMS.cameraPosition[2] = constants.cameraPosZ();
        OPT_EVAL_PARAMS.cameraUp[0] = constants.cameraUpX();
        OPT_EVAL_PARAMS.cameraUp[1] = constants.cameraUpY();
        OPT_EVAL_PARAMS.cameraUp[2] = constants.cameraUpZ();
        OPT_EVAL_PARAMS.cameraRight[0] = constants.cameraRightX();
        OPT_EVAL_PARAMS.cameraRight[1] = constants.cameraRightY();
        OPT_EVAL_PARAMS.cameraRight[2] = constants.cameraRightZ();
        OPT_EVAL_PARAMS.cameraForward[0] = constants.cameraFwdX();
        OPT_EVAL_PARAMS.cameraForward[1] = constants.cameraFwdY();
        OPT_EVAL_PARAMS.cameraForward[2] = constants.cameraFwdZ();
        OPT_EVAL_PARAMS.cameraNear = constants.cameraNear();
        OPT_EVAL_PARAMS.cameraFar = constants.cameraFar();
        OPT_EVAL_PARAMS.cameraFov = constants.cameraFov();
        OPT_EVAL_PARAMS.cameraAspectRatio = constants.cameraAspectRatio();
        OPT_EVAL_PARAMS.colorBuffersHdr = false;
        OPT_EVAL_PARAMS.depthInverted = constants.depthInverted() != 0;
        OPT_EVAL_PARAMS.cameraMotionIncluded = constants.cameraMotionIncluded() != 0;
        OPT_EVAL_PARAMS.reset = constants.reset() != 0;
        OPT_EVAL_PARAMS.automodeOverrideReset = false;
        OPT_EVAL_PARAMS.notRenderingGameFrames = false;
        OPT_EVAL_PARAMS.orthoProjection = constants.orthographicProjection() != 0;
        OPT_EVAL_PARAMS.motionVectorsInvalidValue = constants.motionVectorsInvalidValue();
        OPT_EVAL_PARAMS.motionVectorsDilated = constants.motionVectorsDilated() != 0;
        OPT_EVAL_PARAMS.motionVectorsJittered = constants.motionVectorsJittered() != 0;
        OPT_EVAL_PARAMS.menuDetectionEnabled = false;
        OPT_EVAL_PARAMS.minRelativeLinearDepthObjectSeparation =
                constants.minRelativeLinearDepthObjectSeparation();
        OPT_EVAL_PARAMS.multiFrameCount = generatedFrameCount;
        OPT_EVAL_PARAMS.multiFrameIndex = 1;
    }

    private static NgxResourceVK createResource(VulkanTexture texture, boolean readWrite) {
        NgxImageSubresourceRange subresourceRange = new NgxImageSubresourceRange();
        subresourceRange.aspectMask = texture.getAspectMask();
        subresourceRange.baseMipLevel = 0;
        subresourceRange.levelCount = texture.getMipmapSettings().getLevels();
        subresourceRange.baseArrayLayer = 0;
        subresourceRange.layerCount = 1;
        return NgxVulkan.createImageViewResourceVK(
                texture.getImageView(),
                texture.handle(),
                subresourceRange,
                texture.getTextureFormat().vk(),
                texture.getWidth(),
                texture.getHeight(),
                readWrite
        );
    }

    private static void ensureMatrixBuffers() {
        if (cameraViewToClip == null) {
            cameraViewToClip = MemoryUtil.memAllocFloat(16);
            clipToCameraView = MemoryUtil.memAllocFloat(16);
            clipToLensClip = MemoryUtil.memAllocFloat(16);
            clipToPrevClip = MemoryUtil.memAllocFloat(16);
            prevClipToClip = MemoryUtil.memAllocFloat(16);
        }
    }

    private static void freeMatrixBuffers() {
        if (cameraViewToClip != null) {
            MemoryUtil.memFree(cameraViewToClip);
            MemoryUtil.memFree(clipToCameraView);
            MemoryUtil.memFree(clipToLensClip);
            MemoryUtil.memFree(clipToPrevClip);
            MemoryUtil.memFree(prevClipToClip);
            cameraViewToClip = null;
            clipToCameraView = null;
            clipToLensClip = null;
            clipToPrevClip = null;
            prevClipToClip = null;
        }
    }

    private static void putMatrix(FloatBuffer buffer, float[] values) {
        buffer.clear();
        buffer.put(values);
        buffer.flip();
    }

    private static void requireNgxSuccess(String operation, int result) {
        if (!NgxConstants.succeeded(result)) {
            throw new IllegalStateException(operation + " failed. NGX result: " + result);
        }
    }

    private static void reportFailureOnce(String key, String message, Throwable throwable) {
        if (!REPORTED_FAILURES.add(key)) {
            return;
        }
        if (throwable == null) {
            Wisteria.LOGGER.warn(message);
        } else {
            Wisteria.LOGGER.warn(message, throwable);
        }
    }

    public record PrepareResult(
            List<VulkanTexture> generatedFrames,
            VulkanTexture realFrame
    ) {
    }

    private record FeatureKey(
            int colorWidth,
            int colorHeight,
            int backbufferFormat,
            int renderWidth,
            int renderHeight
    ) {
    }
}
