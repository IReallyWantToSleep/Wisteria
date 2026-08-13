/*
 * Super Resolution
 * Copyright (c) 2026. 187J3X1-114514
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.ireallywanttosleep.wisteria.backend;

import io.homo.superresolution.api.registry.AsyncFrameGenerationDispatchRequest;
import io.homo.superresolution.api.registry.AsyncFrameGenerationDispatchResult;
import io.homo.superresolution.api.registry.FrameGenerationDispatchCompletion;
import io.homo.superresolution.api.registry.ProviderOutputLease;
import io.homo.superresolution.common.framegeneration.constants.FGConstants;
import io.homo.superresolution.common.presentation.capture.FrameResources;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Raw NVNGX DLSS-G implementation for the application-managed async provider contract.
 * All NGX feature/session, output-slot, layout, and lease state is FG-thread-affine.
 */
public final class NgxFrameGenerationAdapter {
    private static final int MIN_WIDTH_OR_HEIGHT = 128;
    private static final int MIN_OUTPUT_SLOT_COUNT = 2;
    // Mirrors AsyncFrameGenerationScheduler.PRESENT_QUEUE_CAPACITY, which is not public.
    private static final int SCHEDULER_PRESENT_QUEUE_CAPACITY = 6;
    private static final int MAX_GENERATED_FRAMES = 5;
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    // Read without the class monitor by isAvailable()/supportedGeneratedFrameCount().
    private static volatile boolean supportQueried;
    private static volatile int maxGeneratedFrameCount;
    private static NgxParameters ngxParameters;
    private static NgxFeature ngxFeature;
    private static FeatureKey featureKey;
    private static SlotKey outputKey;
    private static final List<OutputSlot> outputSlots = new ArrayList<>();
    private static final NgxDLSSFGOptEvalParams optEvalParams = new NgxDLSSFGOptEvalParams();
    private static FloatBuffer cameraViewToClip;
    private static FloatBuffer clipToCameraView;
    private static FloatBuffer clipToLensClip;
    private static FloatBuffer clipToPrevClip;
    private static FloatBuffer prevClipToClip;
    private static Thread fgThread;
    private static boolean historyInvalid;

    private NgxFrameGenerationAdapter() {
    }

    /** Thread-agnostic setup only; NGX session resources are created by dispatchAsync. */
    public static synchronized void initialize() {
        supportQueried = false;
        maxGeneratedFrameCount = 0;
        historyInvalid = false;
        fgThread = null;
        REPORTED_FAILURES.clear();
    }

    /**
     * Terminal thread-affine teardown, invoked by the shared scheduler after every output
     * lease has drained and its FG submission completion has been awaited.
     */
    public static synchronized void shutdownOnFrameGenerationThread() {
        requireFgThread();
        releaseFeature(true);
        freeMatrixBuffers();
        historyInvalid = false;
        fgThread = null;
    }

    /** Thread-agnostic post-FG teardown; it must not touch NGX session/output resources. */
    public static synchronized void shutdown() {
        if (ngxFeature != null || ngxParameters != null || !outputSlots.isEmpty()) {
            throw new IllegalStateException(
                    "NGX session resources were not released on the frame-generation thread"
            );
        }
        supportQueried = false;
        maxGeneratedFrameCount = 0;
        historyInvalid = false;
        REPORTED_FAILURES.clear();
    }

    /**
     * Deliberately not {@code synchronized}. The render thread reaches this on every queued
     * frame through {@code FrameGeneration.plannedGeneratedFrameCount()}, while the FG
     * thread holds this class's monitor for the whole of {@link #dispatchAsync} - and, when
     * a session is created, across a blocking queue wait. Taking the monitor here would put
     * the render thread back behind dispatch, which is the coupling application-managed
     * dispatch exists to remove. Both fields are volatile; only the one-time query locks.
     */
    public static boolean isAvailable() {
        if (!supportQueried) {
            refreshSupport();
        }
        return maxGeneratedFrameCount > 0;
    }

    /** Lock-free for the same reason as {@link #isAvailable()}. */
    public static int supportedGeneratedFrameCount() {
        if (!supportQueried) {
            refreshSupport();
        }
        return maxGeneratedFrameCount;
    }

    public static int minimumWidthOrHeight() {
        return MIN_WIDTH_OR_HEIGHT;
    }

    /**
     * Records one complete NGX dispatch into the scheduler-owned FG command buffer. Failed
     * results intentionally contain neither an output nor a lease, so the scheduler can use
     * its single Real-only fallback path.
     */
    public static synchronized AsyncFrameGenerationDispatchResult dispatchAsync(
            AsyncFrameGenerationDispatchRequest request
    ) {
        requireFgThread();
        if (request == null) {
            return AsyncFrameGenerationDispatchResult.failed("NGX dispatch request is null");
        }
        try {
            if (!isAvailable()) {
                return AsyncFrameGenerationDispatchResult.failed("NGX DLSS-G is unavailable");
            }

            FrameResources frameResources = request.frameResources();
            VulkanTexture backbuffer = frameResources.finalColorVulkanTexture();
            VulkanTexture hudless = frameResources.hudlessColorVulkanTexture();
            VulkanTexture depth = frameResources.depthVulkanTexture();
            VulkanTexture motionVectors = frameResources.motionVectorVulkanTexture();
            String inputFailure = inputFailureReason(request, backbuffer, hudless, depth, motionVectors);
            if (inputFailure != null) {
                return AsyncFrameGenerationDispatchResult.failed(
                        "NGX DLSS-G frame inputs are incompatible: " + inputFailure
                );
            }

            int generatedFrameCount = Math.min(
                    Math.min(request.requestedGeneratedFrameCount(), supportedGeneratedFrameCount()),
                    Math.min(MAX_GENERATED_FRAMES, request.commandBufferCount())
            );
            if (generatedFrameCount <= 0) {
                return AsyncFrameGenerationDispatchResult.failed("NGX DLSS-G requested no generated frames");
            }

            boolean sessionCreated = ensureFeature(request.device(), backbuffer, depth);
            OutputSlot slot = acquireOutputSlot(request, backbuffer, generatedFrameCount);
            if (slot == null) {
                return AsyncFrameGenerationDispatchResult.failed("No reusable NGX DLSS-G output slot");
            }

            List<LayoutTransition> layoutTransitions = List.of();
            try {
                VulkanTexture realOutput = slot.realOutput();
                List<VulkanTexture> generatedOutputs = slot.generatedOutputs(generatedFrameCount);
                layoutTransitions = recordTransitionsToGeneral(
                        request.device(),
                        request.commandBuffer(),
                        backbuffer,
                        hudless,
                        depth,
                        motionVectors,
                        generatedOutputs,
                        realOutput
                );
                boolean resetHistory = historyInvalid
                        || request.providerInputSnapshot().historyResetRequested();
                fillOptEvalParams(
                        request.providerInputSnapshot().constants(),
                        generatedFrameCount,
                        resetHistory
                );

                // One command buffer per generated frame. The scheduler submits them
                // separately in index order, so generated frame k's present semaphore
                // signals when its own evaluation retires instead of waiting for the whole
                // batch - what the DLSS-FG programming guide asks for when it says the
                // first generated frame must not wait for the later ones. The shared
                // transitions above stay in request.commandBuffer(), which is index 0 and
                // therefore submitted first.
                for (int frameIndex = 1; frameIndex <= generatedFrameCount; frameIndex++) {
                    optEvalParams.multiFrameCount = generatedFrameCount;
                    optEvalParams.multiFrameIndex = frameIndex;
                    int result = evaluate(
                            request.generatedFrameCommandBuffer(frameIndex - 1),
                            backbuffer,
                            depth,
                            motionVectors,
                            hudless,
                            generatedOutputs.get(frameIndex - 1),
                            realOutput
                    );
                    if (!NgxConstants.succeeded(result)) {
                        reportFailureOnce(
                                "evaluate-" + result,
                                "NGX DLSS-G evaluation failed for frame "
                                        + frameResources.logicalFrameIndex() + " with result=" + result,
                                null
                        );
                        historyInvalid = true;
                        restoreLayouts(layoutTransitions);
                        slot.abort();
                        return AsyncFrameGenerationDispatchResult.failed(
                                "NGX DLSS-G evaluation failed with result=" + result
                        );
                    }
                }
                for (VulkanTexture output : generatedOutputs) {
                    output.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
                }
                realOutput.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL);
                historyInvalid = false;
                return AsyncFrameGenerationDispatchResult.success(
                        generatedFrameCount,
                        slot.lease(generatedOutputs, layoutTransitions),
                        resetHistory
                                ? AsyncFrameGenerationDispatchResult.HistoryDisposition.RESET
                                : sessionCreated
                                ? AsyncFrameGenerationDispatchResult.HistoryDisposition.SEEDED
                                : AsyncFrameGenerationDispatchResult.HistoryDisposition.UNCHANGED
                );
            } catch (Throwable throwable) {
                restoreLayouts(layoutTransitions);
                slot.abort();
                historyInvalid = true;
                reportFailureOnce("dispatch", "NGX DLSS-G dispatch failed", throwable);
                return AsyncFrameGenerationDispatchResult.failed("NGX DLSS-G dispatch failed");
            }
        } catch (Throwable throwable) {
            historyInvalid = true;
            reportFailureOnce("dispatch-setup", "NGX DLSS-G dispatch setup failed", throwable);
            return AsyncFrameGenerationDispatchResult.failed("NGX DLSS-G dispatch setup failed");
        }
    }

    public static synchronized void disable() {
        // Keep the session resident. The next enabled dispatch receives a fresh snapshot.
    }

    private static String inputFailureReason(
            AsyncFrameGenerationDispatchRequest request,
            VulkanTexture backbuffer,
            VulkanTexture hudless,
            VulkanTexture depth,
            VulkanTexture motionVectors
    ) {
        if (backbuffer == null) {
            return "backbuffer is missing";
        }
        if (hudless == null) {
            return "hudless color is missing";
        }
        if (depth == null) {
            return "depth is missing";
        }
        if (motionVectors == null) {
            return "motion vectors are missing";
        }
        if (backbuffer.getWidth() != request.outputWidth()
                || backbuffer.getHeight() != request.outputHeight()) {
            return "backbuffer extent "
                    + backbuffer.getWidth() + "x" + backbuffer.getHeight()
                    + " != swapchain extent "
                    + request.outputWidth() + "x" + request.outputHeight();
        }
        if (hudless.getWidth() != request.outputWidth()
                || hudless.getHeight() != request.outputHeight()) {
            return "hudless extent "
                    + hudless.getWidth() + "x" + hudless.getHeight()
                    + " != swapchain extent "
                    + request.outputWidth() + "x" + request.outputHeight();
        }
        if (depth.getWidth() != motionVectors.getWidth()
                || depth.getHeight() != motionVectors.getHeight()) {
            return "depth extent "
                    + depth.getWidth() + "x" + depth.getHeight()
                    + " != motion-vector extent "
                    + motionVectors.getWidth() + "x" + motionVectors.getHeight();
        }
        if (request.outputWidth() < MIN_WIDTH_OR_HEIGHT
                || request.outputHeight() < MIN_WIDTH_OR_HEIGHT) {
            return "swapchain extent is below the NGX minimum "
                    + MIN_WIDTH_OR_HEIGHT + "px";
        }
        return null;
    }

    private static int evaluate(
            long commandBuffer,
            VulkanTexture backbuffer,
            VulkanTexture depth,
            VulkanTexture motionVectors,
            VulkanTexture hudless,
            VulkanTexture output,
            VulkanTexture realOutput
    ) {
        try (
                NgxResourceVK backbufferResource = createResource(backbuffer, false);
                NgxResourceVK depthResource = createResource(depth, false);
                NgxResourceVK motionVectorsResource = createResource(motionVectors, false);
                NgxResourceVK hudlessResource = createResource(hudless, false);
                NgxResourceVK outputResource = createResource(output, true);
                NgxResourceVK realOutputResource = createResource(realOutput, true)
        ) {
            NgxVKDLSSFGEvalParams evalParams = new NgxVKDLSSFGEvalParams();
            evalParams.backbuffer = backbufferResource;
            evalParams.depth = depthResource;
            evalParams.motionVectors = motionVectorsResource;
            evalParams.hudless = hudlessResource;
            evalParams.outputInterpolatedFrame = outputResource;
            evalParams.outputRealFrame = realOutputResource;
            return NgxVulkan.evaluateDLSSFG(
                    commandBuffer,
                    ngxFeature,
                    ngxParameters,
                    evalParams,
                    optEvalParams
            );
        }
    }

    private static void refreshSupport() {
        supportQueried = true;
        maxGeneratedFrameCount = 0;
        if (!NgxInitializer.initializeIfSupported()) {
            return;
        }
        NgxParameters capabilities = new NgxParameters();
        int capabilitiesResult = NgxVulkan.getCapabilityParameters(capabilities);
        if (!NgxConstants.succeeded(capabilitiesResult)) {
            reportFailureOnce(
                    "capability-" + capabilitiesResult,
                    "NVSDK_NGX_VULKAN_GetCapabilityParameters failed with result=" + capabilitiesResult,
                    null
            );
            return;
        }
        try {
            int[] available = new int[1];
            int availableResult = capabilities.getInt(NgxConstants.FRAME_GENERATION_AVAILABLE, available);
            if (!NgxConstants.succeeded(availableResult) || available[0] == 0) {
                logUnavailableReason(capabilities);
                return;
            }
            long[] multiFrameCountMax = new long[1];
            int multiFrameResult = capabilities.getUnsignedInt(
                    NgxConstants.DLSSFG_MULTI_FRAME_COUNT_MAX,
                    multiFrameCountMax
            );
            maxGeneratedFrameCount = NgxConstants.succeeded(multiFrameResult) && multiFrameCountMax[0] > 1
                    ? Math.min((int) multiFrameCountMax[0], MAX_GENERATED_FRAMES)
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
        capabilities.getUnsignedInt(NgxConstants.FRAME_GENERATION_MIN_DRIVER_VERSION_MAJOR, driverMajor);
        capabilities.getUnsignedInt(NgxConstants.FRAME_GENERATION_MIN_DRIVER_VERSION_MINOR, driverMinor);
        reportFailureOnce(
                "unavailable",
                "NGX DLSS-G is not available on this system. FeatureInitResult=" + initResult[0]
                        + ", needsUpdatedDriver=" + needsDriver[0]
                        + ", minimum driver=" + driverMajor[0] + "." + driverMinor[0],
                null
        );
    }

    private static boolean ensureFeature(
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
            return false;
        }
        requireNoLeasedSlots();
        releaseFeature(false);

        NgxParameters parameters = new NgxParameters();
        NgxFeature feature = new NgxFeature();
        VulkanCommandBuffer commandBuffer = device.requireFgCommandPool().createCommandBuffer();
        try {
            int parametersResult = NgxVulkan.getCapabilityParameters(parameters);
            requireNgxSuccess("NVSDK_NGX_VULKAN_GetCapabilityParameters", parametersResult);

            NgxDLSSFGCreateParams createParams = new NgxDLSSFGCreateParams();
            createParams.width = desired.colorWidth();
            createParams.height = desired.colorHeight();
            createParams.nativeBackbufferFormat = desired.backbufferFormat();
            createParams.renderWidth = desired.renderWidth();
            createParams.renderHeight = desired.renderHeight();
            createParams.dynamicResolutionScaling = false;

            commandBuffer.begin();
            parameters.setUnsignedInt("DLSSG.UserInterfaceRecompositionEnabled",1);
            int createResult = NgxVulkan.createDLSSFG(
                    commandBuffer.getNativeCommandBuffer().address(),
                    1,
                    1,
                    feature,
                    parameters,
                    createParams
            );
            commandBuffer.end();
            requireNgxSuccess("NGX_VK_CREATE_DLSSG", createResult);
            device.submitCommandBuffer(device.requireFgQueue(), commandBuffer);
            commandBuffer.waitForFence();

            ngxParameters = parameters;
            ngxFeature = feature;
            featureKey = desired;
            Wisteria.LOGGER.info(
                    "Created NGX DLSS-G feature on FG queue: color {}x{} (VkFormat {}), render {}x{}",
                    desired.colorWidth(),
                    desired.colorHeight(),
                    desired.backbufferFormat(),
                    desired.renderWidth(),
                    desired.renderHeight()
            );
            return true;
        } catch (RuntimeException | Error e) {
            feature.close();
            parameters.close();
            throw e;
        } finally {
            commandBuffer.destroy();
        }
    }

    private static OutputSlot acquireOutputSlot(
            AsyncFrameGenerationDispatchRequest request,
            VulkanTexture backbuffer,
            int generatedFrameCount
    ) {
        SlotKey desired = new SlotKey(
                request.outputWidth(),
                request.outputHeight(),
                backbuffer.getTextureFormat().vk(),
                generatedFrameCount
        );
        if (!desired.equals(outputKey)) {
            if (hasLeasedSlots()) {
                // Batches queued at the old size or multiplier are still presenting from
                // these textures. Reporting no slot lets the scheduler take its Real-only
                // path for a frame or two until they drain, which is cheaper and quieter
                // than throwing out of dispatch.
                return null;
            }
            int slotCount = outputSlotCount(generatedFrameCount);
            List<OutputSlot> replacementSlots = new ArrayList<>(slotCount);
            try {
                for (int index = 0; index < slotCount; index++) {
                    replacementSlots.add(createOutputSlot(
                            request.device(),
                            backbuffer,
                            index,
                            generatedFrameCount
                    ));
                }
            } catch (RuntimeException | Error e) {
                for (OutputSlot slot : replacementSlots) {
                    slot.destroy();
                }
                throw e;
            }
            destroyOutputSlots();
            outputSlots.addAll(replacementSlots);
            outputKey = desired;
            Wisteria.LOGGER.info(
                    "Allocated {} DLSS-G output slots of {} interpolated frame(s) at {}x{}",
                    slotCount,
                    generatedFrameCount,
                    desired.width(),
                    desired.height()
            );
        }
        for (OutputSlot slot : outputSlots) {
            if (slot.acquire()) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Slots are sized per multiplier rather than for the worst case: allocating
     * {@link #MAX_GENERATED_FRAMES} outputs per slot would leave most of them untouched at
     * 2x or 3x, which is where the setting usually sits.
     * <p>
     * The count mirrors how many batches the scheduler can have outstanding: its present
     * queue holds {@code SCHEDULER_PRESENT_QUEUE_CAPACITY} frames, so that many batches of
     * {@code generatedFrameCount + 1} can be queued, plus the one the present thread is
     * draining. That constant is not public API - if Super Resolution grows the queue this
     * only under-provisions, which costs a Real-only frame rather than correctness.
     */
    private static int outputSlotCount(int generatedFrameCount) {
        return Math.max(
                MIN_OUTPUT_SLOT_COUNT,
                SCHEDULER_PRESENT_QUEUE_CAPACITY / (generatedFrameCount + 1) + 1
        );
    }

    private static boolean hasLeasedSlots() {
        for (OutputSlot slot : outputSlots) {
            if (slot.leased) {
                return true;
            }
        }
        return false;
    }

    private static OutputSlot createOutputSlot(
            VulkanDevice device,
            VulkanTexture source,
            int index,
            int generatedFrameCount
    ) {
        List<VulkanTexture> generated = new ArrayList<>(generatedFrameCount);
        VulkanTexture real = null;
        try {
            for (int outputIndex = 0; outputIndex < generatedFrameCount; outputIndex++) {
                generated.add(createOutputTexture(
                        device,
                        source,
                        "SRDlssGSlot-" + index + "-Generated-" + outputIndex
                ));
            }
            real = createOutputTexture(device, source, "SRDlssGSlot-" + index + "-Real");
            return new OutputSlot(generated, real);
        } catch (RuntimeException | Error e) {
            for (VulkanTexture output : generated) {
                output.destroy();
            }
            if (real != null) {
                real.destroy();
            }
            throw e;
        }
    }

    private static VulkanTexture createOutputTexture(
            VulkanDevice device,
            VulkanTexture source,
            String label
    ) {
        TextureDescription description = TextureDescription.create()
                .type(TextureType.Texture2D)
                .format(source.getTextureFormat())
                .size(source.getWidth(), source.getHeight())
                .usages(TextureUsages.create()
                        .sampler()
                        .storage()
                        .transferSource()
                        .transferDestination())
                .label(label)
                .build();
        return (VulkanTexture) device.createTexture(description);
    }

    private static void releaseFeature(boolean quiet) {
        requireNoLeasedSlots();
        if (ngxFeature != null) {
            int result = ngxFeature.release();
            if (!NgxConstants.succeeded(result) && !quiet) {
                Wisteria.LOGGER.warn("Failed to release the NGX DLSS-G feature. Result: {}", result);
            }
            ngxFeature = null;
        }
        if (ngxParameters != null) {
            int result = ngxParameters.destroy();
            if (!NgxConstants.succeeded(result) && !quiet) {
                Wisteria.LOGGER.warn("Failed to destroy the NGX DLSS-G parameters. Result: {}", result);
            }
            ngxParameters = null;
        }
        destroyOutputSlots();
        featureKey = null;
        outputKey = null;
    }

    private static void destroyOutputSlots() {
        for (OutputSlot slot : outputSlots) {
            slot.destroy();
        }
        outputSlots.clear();
    }

    private static void requireNoLeasedSlots() {
        for (OutputSlot slot : outputSlots) {
            if (slot.leased) {
                throw new IllegalStateException("NGX output slots are still leased");
            }
        }
    }

    private static List<LayoutTransition> recordTransitionsToGeneral(
            VulkanDevice device,
            long commandBuffer,
            VulkanTexture backbuffer,
            VulkanTexture hudless,
            VulkanTexture depth,
            VulkanTexture motionVectors,
            List<VulkanTexture> generatedOutputs,
            VulkanTexture realOutput
    ) {
        List<VulkanTexture> textures = new ArrayList<>();
        addIfNotGeneral(textures, backbuffer);
        addIfNotGeneral(textures, hudless);
        addIfNotGeneral(textures, depth);
        addIfNotGeneral(textures, motionVectors);
        for (VulkanTexture output : generatedOutputs) {
            addIfNotGeneral(textures, output);
        }
        addIfNotGeneral(textures, realOutput);
        if (textures.isEmpty()) {
            return List.of();
        }
        List<LayoutTransition> transitions = new ArrayList<>(textures.size());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(textures.size(), stack);
            for (int index = 0; index < textures.size(); index++) {
                VulkanTexture texture = textures.get(index);
                int oldLayout = texture.getCurrentLayout();
                transitions.add(new LayoutTransition(texture, oldLayout));
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
        return List.copyOf(transitions);
    }

    private static void restoreLayouts(List<LayoutTransition> transitions) {
        for (LayoutTransition transition : transitions) {
            transition.texture().setCurrentLayout(transition.oldLayout());
        }
    }

    private static void addIfNotGeneral(List<VulkanTexture> textures, VulkanTexture texture) {
        if (texture.getCurrentLayout() != VK_IMAGE_LAYOUT_GENERAL) {
            textures.add(texture);
        }
    }

    private static void fillOptEvalParams(
            FGConstants constants,
            int generatedFrameCount,
            boolean reset
    ) {
        ensureMatrixBuffers();
        putMatrix(cameraViewToClip, constants.cameraViewToClip());
        putMatrix(clipToCameraView, constants.clipToCameraView());
        putMatrix(clipToLensClip, constants.clipToLensClip());
        putMatrix(clipToPrevClip, constants.clipToPrevClip());
        putMatrix(prevClipToClip, constants.prevClipToClip());
        optEvalParams.cameraViewToClip = cameraViewToClip;
        optEvalParams.clipToCameraView = clipToCameraView;
        optEvalParams.clipToLensClip = clipToLensClip;
        optEvalParams.clipToPrevClip = clipToPrevClip;
        optEvalParams.prevClipToClip = prevClipToClip;
        optEvalParams.jitterOffset[0] = constants.jitterOffsetX();
        optEvalParams.jitterOffset[1] = constants.jitterOffsetY();
        optEvalParams.motionVectorScale[0] = constants.motionVectorScaleX();
        optEvalParams.motionVectorScale[1] = constants.motionVectorScaleY();
        optEvalParams.cameraPinholeOffset[0] = constants.cameraPinholeOffsetX();
        optEvalParams.cameraPinholeOffset[1] = constants.cameraPinholeOffsetY();
        optEvalParams.cameraPosition[0] = constants.cameraPosX();
        optEvalParams.cameraPosition[1] = constants.cameraPosY();
        optEvalParams.cameraPosition[2] = constants.cameraPosZ();
        optEvalParams.cameraUp[0] = constants.cameraUpX();
        optEvalParams.cameraUp[1] = constants.cameraUpY();
        optEvalParams.cameraUp[2] = constants.cameraUpZ();
        optEvalParams.cameraRight[0] = constants.cameraRightX();
        optEvalParams.cameraRight[1] = constants.cameraRightY();
        optEvalParams.cameraRight[2] = constants.cameraRightZ();
        optEvalParams.cameraForward[0] = constants.cameraFwdX();
        optEvalParams.cameraForward[1] = constants.cameraFwdY();
        optEvalParams.cameraForward[2] = constants.cameraFwdZ();
        optEvalParams.cameraNear = constants.cameraNear();
        optEvalParams.cameraFar = constants.cameraFar();
        optEvalParams.cameraFov = constants.cameraFov();
        optEvalParams.cameraAspectRatio = constants.cameraAspectRatio();
        optEvalParams.colorBuffersHdr = false;
        optEvalParams.depthInverted = constants.depthInverted() != 0;
        optEvalParams.cameraMotionIncluded = constants.cameraMotionIncluded() != 0;
        optEvalParams.reset = reset;
        optEvalParams.automodeOverrideReset = false;
        optEvalParams.notRenderingGameFrames = false;
        optEvalParams.orthoProjection = constants.orthographicProjection() != 0;
        optEvalParams.motionVectorsInvalidValue = constants.motionVectorsInvalidValue();
        optEvalParams.motionVectorsDilated = constants.motionVectorsDilated() != 0;
        optEvalParams.motionVectorsJittered = constants.motionVectorsJittered() != 0;
        optEvalParams.menuDetectionEnabled = false;
        optEvalParams.minRelativeLinearDepthObjectSeparation =
                constants.minRelativeLinearDepthObjectSeparation();
        optEvalParams.multiFrameCount = generatedFrameCount;
        optEvalParams.multiFrameIndex = 1;
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

    private static void requireFgThread() {
        Thread current = Thread.currentThread();
        if (fgThread == null) {
            fgThread = current;
        } else if (fgThread != current) {
            throw new IllegalStateException("NGX feature/session access must stay on the FG thread");
        }
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

    private static final class OutputSlot {
        private final List<VulkanTexture> generatedOutputs;
        private final VulkanTexture realOutput;
        private boolean leased;

        private OutputSlot(List<VulkanTexture> generatedOutputs, VulkanTexture realOutput) {
            this.generatedOutputs = List.copyOf(generatedOutputs);
            this.realOutput = realOutput;
        }

        private boolean acquire() {
            if (leased) {
                return false;
            }
            leased = true;
            return true;
        }

        private List<VulkanTexture> generatedOutputs(int count) {
            return generatedOutputs.subList(0, count);
        }

        private VulkanTexture realOutput() {
            return realOutput;
        }

        private ProviderOutputLease lease(
                List<VulkanTexture> actualGeneratedOutputs,
                List<LayoutTransition> initialLayouts
        ) {
            return new SlotLease(this, actualGeneratedOutputs, outputKey, initialLayouts);
        }

        private void abort() {
            leased = false;
        }

        private void destroy() {
            if (leased) {
                throw new IllegalStateException("Cannot destroy an NGX output slot while leased");
            }
            for (VulkanTexture output : generatedOutputs) {
                output.destroy();
            }
            realOutput.destroy();
        }
    }

    private static final class SlotLease implements ProviderOutputLease {
        private final OutputSlot slot;
        private final List<VulkanTexture> generatedOutputs;
        private final OutputKey outputKey;
        private final List<LayoutTransition> initialLayouts;
        private boolean released;

        private SlotLease(
                OutputSlot slot,
                List<VulkanTexture> generatedOutputs,
                SlotKey slotKey,
                List<LayoutTransition> initialLayouts
        ) {
            if (slotKey == null) {
                throw new IllegalStateException("NGX output key is unavailable");
            }
            this.slot = slot;
            this.generatedOutputs = List.copyOf(generatedOutputs);
            this.outputKey = new OutputKey(slotKey.width, slotKey.height, slotKey.format);
            this.initialLayouts = List.copyOf(initialLayouts);
        }

        @Override
        public List<VulkanTexture> generatedOutputs() {
            return generatedOutputs;
        }

        @Override
        public VulkanTexture realOutput() {
            return slot.realOutput;
        }

        @Override
        public FrameGenerationDispatchCompletion completion() {
            return FrameGenerationDispatchCompletion.completed();
        }

        @Override
        public OutputKey outputKey() {
            return outputKey;
        }

        @Override
        public boolean isReleased() {
            return released;
        }

        @Override
        public void abort() {
            synchronized (NgxFrameGenerationAdapter.class) {
                requireFgThread();
                if (!released) {
                    restoreLayouts(initialLayouts);
                    released = true;
                    slot.leased = false;
                }
            }
        }

        @Override
        public void release() {
            synchronized (NgxFrameGenerationAdapter.class) {
                requireFgThread();
                if (!released) {
                    released = true;
                    slot.leased = false;
                }
            }
        }
    }

    private record LayoutTransition(VulkanTexture texture, int oldLayout) {
    }

    private record SlotKey(int width, int height, int format, int generatedFrameCount) {
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
