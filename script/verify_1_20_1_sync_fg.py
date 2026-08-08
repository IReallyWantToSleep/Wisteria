#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]


def read(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        errors.append(f"missing required file: {path}")
        return ""
    return path.read_text(encoding="utf-8")


def require(source: str, markers: tuple[str, ...], label: str, errors: list[str]) -> None:
    for marker in markers:
        if marker not in source:
            errors.append(f"{label} missing contract marker: {marker}")


def forbid(source: str, markers: tuple[str, ...], label: str, errors: list[str]) -> None:
    for marker in markers:
        if marker in source:
            errors.append(f"{label} retains forbidden marker: {marker}")


def require_in_order(
    source: str,
    markers: tuple[str, ...],
    label: str,
    errors: list[str],
) -> None:
    offset = 0
    for marker in markers:
        index = source.find(marker, offset)
        if index < 0:
            errors.append(f"{label} missing ordered contract marker: {marker}")
            return
        offset = index + len(marker)


def verify_wisteria(errors: list[str]) -> None:
    profile = read(ROOT / "versions/1.20.1.properties", errors)
    common_build = read(ROOT / "common/build.gradle.kts", errors)
    forge_build = read(ROOT / "forge/build.gradle.kts", errors)
    forge_entry = read(
        ROOT / "forge/src/main/java/org/ireallywanttosleep/wisteria/forge/WisteriaForge.java",
        errors,
    )
    forge_metadata = read(ROOT / "forge/src/main/resources/META-INF/mods.toml", errors)
    registration = read(
        ROOT
        / "common/src/main/java/org/ireallywanttosleep/wisteria/backend/WisteriaFrameGeneration.java",
        errors,
    )
    backend = read(
        ROOT
        / "common/src/main/java/org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationBackend.java",
        errors,
    )
    adapter = read(
        ROOT
        / "common/src/main/java/org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationAdapter.java",
        errors,
    )
    streamline = read(
        ROOT
        / "common/src/main/java/org/ireallywanttosleep/wisteria/backend/StreamlineFrameGenerationBackend.java",
        errors,
    )

    require(
        profile,
        (
            "minecraft_version=1.20.1",
            "java_version=17",
            "legacy_forge=true",
            "loaders=forge",
            "forge_version=47.3.1",
        ),
        "1.20.1 profile",
        errors,
    )
    require(
        common_build,
        (
            'apply(plugin = "net.neoforged.moddev.legacyforge")',
            'setProperty("mcpVersion", cfg("minecraft_version"))',
        ),
        "common LegacyForge build",
        errors,
    )
    require(
        forge_build,
        (
            'id("net.neoforged.moddev.legacyforge")',
            "implementation(commonMain.output)",
            "from(commonMain.output)",
        ),
        "Forge loader build",
        errors,
    )
    require(
        forge_entry,
        ("@Mod(Wisteria.MOD_ID)", "Wisteria.init(FMLPaths.GAMEDIR.get())"),
        "Forge entrypoint",
        errors,
    )
    require(
        forge_metadata,
        (
            'modId = "${sr_mod_id}"',
            "mandatory = true",
            'ordering = "BEFORE"',
            'side = "CLIENT"',
        ),
        "Forge metadata",
        errors,
    )
    require(
        registration,
        (
            "private static boolean listenerInstalled;",
            "if (listenerInstalled)",
            ".providerFactory(NgxFrameGenerationBackend::new)",
            "listenerInstalled = true;",
            'Wisteria.LOGGER.info("Wisteria FG listener installed")',
            'Wisteria.LOGGER.info("Wisteria providers registered:',
        ),
        "provider registration",
        errors,
    )
    forbid(
        registration,
        (
            "APPLICATION_MANAGED_ASYNC",
            ".executionModel(",
            "FrameGenerationExecutionModel",
        ),
        "provider registration",
        errors,
    )
    require(
        backend,
        (
            "FramePresentPlan prepareFrame(",
            "NgxFrameGenerationAdapter.prepareFrame(",
            "FramePresentPlan.generated(result.generatedFrames(), result.realFrame())",
            "Math.max(1, mode.generatedFrameCount())",
            "supportedGeneratedFrameCount()",
            "void finishPresent(FrameResources frameResources, boolean frameGenerationActive)",
        ),
        "NGX provider facade",
        errors,
    )
    forbid(
        backend,
        (
            "FrameGenerationExecutionModel",
            "APPLICATION_MANAGED_ASYNC",
            "dispatchAsync(",
            "shutdownOnFrameGenerationThread",
            "ProviderInputSnapshot",
            "ProviderOutputLease",
            "supportedGeneratedFrameCount() > 0 ? 1 : 0",
        ),
        "NGX provider facade",
        errors,
    )
    require(
        adapter,
        (
            "PrepareResult prepareFrame(",
            "int generatedFrameCount = Math.min(",
            "Math.max(1, mode.generatedFrameCount())",
            "NgxConstants.DLSSFG_MULTI_FRAME_COUNT_MAX",
            "ensureInterpolatedTextures(device, generatedFrameCount, backbuffer)",
            "VulkanCommandBuffer createCommandBuffer = device.createCommandBuffer()",
            "NgxVulkan.createDLSSFG(",
            "device.submitCommandBuffer(createCommandBuffer)",
            "createCommandBuffer.waitForFence()",
            "for (int frameIndex = 1; frameIndex <= generatedFrameCount; frameIndex++)",
            "OPT_EVAL_PARAMS.multiFrameCount = generatedFrameCount",
            "OPT_EVAL_PARAMS.multiFrameIndex = frameIndex",
            "interpolatedTextures.get(frameIndex - 1)",
            "NgxVulkan.evaluateDLSSFG(",
            "backbuffer.getTextureFormat().vk()",
            "device.getMainQueue().waitIdle()",
            "OPT_EVAL_PARAMS.reset = constants.reset() != 0",
            "List.copyOf(interpolatedTextures.subList(0, generatedFrameCount))",
        ),
        "NGX synchronous adapter",
        errors,
    )
    require_in_order(
        adapter,
        (
            "createCommandBuffer.begin()",
            "NgxVulkan.createDLSSFG(",
            "createCommandBuffer.end()",
            "device.submitCommandBuffer(createCommandBuffer)",
            "createCommandBuffer.waitForFence()",
            "ngxParameters = parameters",
            "ngxFeature = feature",
        ),
        "NGX feature creation",
        errors,
    )
    forbid(
        adapter,
        (
            "AsyncFrameGeneration",
            "ProviderInputSnapshot",
            "ProviderOutputLease",
            "OutputSlot",
            "NgxOutputLease",
            "dispatchAsync",
            "historySeeded",
            "historyResetPending",
            "shutdownOnFrameGenerationThread",
            "APPLICATION_MANAGED_ASYNC",
            "MAX_OUTPUT_SLOTS",
            "output lease",
            "GENERATED_FRAME_COUNT",
            "List.of(generatedOutput)",
            "maxGeneratedFrameCount == 1",
        ),
        "NGX synchronous adapter",
        errors,
    )
    require(streamline, ("FramePresentPlan prepareFrame(",), "Streamline backend", errors)
    forbid(
        streamline,
        ("APPLICATION_MANAGED_ASYNC", "dispatchAsync("),
        "Streamline backend",
        errors,
    )

    java_sources = list((ROOT / "common/src/main/java").rglob("*.java")) + list(
        (ROOT / "forge/src/main/java").rglob("*.java")
    )
    for source_path in java_sources:
        source = source_path.read_text(encoding="utf-8")
        for marker in (".getFirst()", ".getLast()", "Thread.ofVirtual(", "Thread.ofPlatform("):
            if marker in source:
                errors.append(f"Java 17 source contains newer API {marker}: {source_path}")
        if "mixin" in source_path.as_posix().lower():
            errors.append(f"Wisteria integration must not add mixins: {source_path}")


def verify_super_resolution(sr_root: Path, errors: list[str]) -> None:
    forge_entry = read(
        sr_root / "forge/src/main/java/io/homo/superresolution/forge/SuperResolutionForge.java",
        errors,
    )
    build = read(sr_root / "build.gradle", errors)
    provider = read(
        sr_root
        / "common/src/main/java/io/homo/superresolution/api/registry/FrameGenerationProvider.java",
        errors,
    )
    frame_generation = read(
        sr_root
        / "common/src/main/java/io/homo/superresolution/common/framegeneration/FrameGeneration.java",
        errors,
    )
    swapchain = read(
        sr_root
        / "common/src/main/java/io/homo/superresolution/common/presentation/vulkan/VulkanSwapchain.java",
        errors,
    )

    forbid(
        forge_entry,
        (
            "bootstrapOptionalWisteriaRegistration",
            "org.ireallywanttosleep.wisteria",
            "Class.forName",
        ),
        "Super Resolution Forge entrypoint",
        errors,
    )
    require(
        build,
        (
            "def apiJar = tasks.register('apiJar', Jar)",
            "from sourceSets.main.output.classesDirs",
            "if (major > 61)",
            "api(MavenPublication)",
            "artifactId = 'superresolution-api'",
        ),
        "Super Resolution API publication",
        errors,
    )
    require(
        provider,
        (
            "default FrameGenerationExecutionModel executionModel()",
            "return FrameGenerationExecutionModel.EXTERNAL_INTERPOSER;",
        ),
        "Super Resolution provider default",
        errors,
    )
    require(
        frame_generation,
        (
            "FramePresentPlan prepareFrame(",
            "provider.executionModel() == FrameGenerationExecutionModel.EXTERNAL_INTERPOSER",
            "return provider.prepareFrame(",
        ),
        "Super Resolution synchronous provider dispatch",
        errors,
    )
    require(
        swapchain,
        (
            "AsyncFrameGenerationScheduler scheduler = this.ensureApplicationManagedScheduler()",
            "FramePresentPlan plan = FrameGeneration.prepareFrame(",
            "private boolean submitGeneratedFrames(FramePresentPlan plan, int realImageIndex)",
            "String providerId = FrameGeneration.activeApplicationManagedProviderId()",
            "if (providerId.isEmpty())",
            "return null;",
        ),
        "Super Resolution synchronous presentation route",
        errors,
    )
    generated_route_start = swapchain.find(
        "private boolean submitGeneratedFrames(FramePresentPlan plan, int realImageIndex)"
    )
    generated_route_end = swapchain.find(
        "private void submitRealOnlyWithoutPresent", generated_route_start
    )
    generated_route = (
        swapchain[generated_route_start:generated_route_end]
        if generated_route_start >= 0 and generated_route_end > generated_route_start
        else ""
    )
    require_in_order(
        generated_route,
        (
            "presentOrder.add(generatedImageIndex)",
            "presentOrder.add(realImageIndex)",
            "VulkanLowLatency.expectGeneratedBatch(generated.size())",
            "this.pacer.submitBatch(presentOrder",
        ),
        "Super Resolution generated-to-real presentation order",
        errors,
    )

    sr_sources = list((sr_root / "common/src/main/java").rglob("*.java")) + list(
        (sr_root / "forge/src/main/java").rglob("*.java")
    )
    for source_path in sr_sources:
        source = source_path.read_text(encoding="utf-8")
        if "org.ireallywanttosleep.wisteria" in source:
            errors.append(f"Super Resolution must not reflect/bootstrap Wisteria: {source_path}")


def verify_wisteria_jar(jar_path: Path, errors: list[str]) -> None:
    if not jar_path.is_file():
        errors.append(f"missing Wisteria runtime JAR: {jar_path}")
        return
    required_entries = {
        "META-INF/mods.toml",
        "pack.mcmeta",
        "org/ireallywanttosleep/wisteria/forge/WisteriaForge.class",
        "org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationBackend.class",
        "org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationAdapter.class",
        "org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationAdapter$PrepareResult.class",
        "org/ireallywanttosleep/wisteria/backend/StreamlineFrameGenerationBackend.class",
        "streamline/index.txt",
        "streamline/NvLowLatencyVk.dll",
        "streamline/nvngx_dlssg.dll",
        "streamline/sl.common.dll",
        "streamline/sl.dlss_g.dll",
        "streamline/sl.interposer.dll",
        "streamline/sl.pcl.dll",
        "streamline/sl.reflex.dll",
    }
    forbidden_entries = {
        "org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationAdapter$NgxOutputLease.class",
        "org/ireallywanttosleep/wisteria/backend/NgxFrameGenerationAdapter$OutputSlot.class",
    }
    with ZipFile(jar_path) as jar:
        names = set(jar.namelist())
        for entry in sorted(required_entries - names):
            errors.append(f"Wisteria runtime JAR missing entry: {entry}")
        for entry in sorted(forbidden_entries & names):
            errors.append(f"Wisteria runtime JAR retains async entry: {entry}")
        if any(name.startswith("io/homo/superresolution/") for name in names):
            errors.append("Wisteria runtime JAR must not bundle Super Resolution classes")
        if any("mixin" in name.lower() for name in names):
            errors.append("Wisteria runtime JAR must not contain mixin configuration/classes")
        metadata = (
            jar.read("META-INF/mods.toml").decode("utf-8")
            if "META-INF/mods.toml" in names
            else ""
        )
        require(
            metadata,
            ('loaderVersion = "[47,)"', 'ordering = "BEFORE"', 'modId = "super_resolution"'),
            "packaged Forge metadata",
            errors,
        )
        if "pack.mcmeta" in names:
            try:
                pack_metadata = json.loads(jar.read("pack.mcmeta").decode("utf-8"))
                if pack_metadata.get("pack", {}).get("pack_format") != 15:
                    errors.append("Wisteria pack.mcmeta must use Forge 1.20.1 pack_format 15")
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                errors.append(f"Wisteria pack.mcmeta is invalid: {error}")
        for name in names:
            if not name.endswith(".class"):
                continue
            header = jar.read(name)[:8]
            if len(header) == 8:
                major = int.from_bytes(header[6:8], "big")
                if major > 61:
                    errors.append(f"Wisteria class is newer than Java 17: {name} (major {major})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sr-root", required=True, type=Path)
    parser.add_argument("--wisteria-jar", required=True, type=Path)
    args = parser.parse_args()
    errors: list[str] = []
    verify_wisteria(errors)
    verify_super_resolution(args.sr_root.resolve(), errors)
    verify_wisteria_jar(args.wisteria_jar.resolve(), errors)
    if errors:
        print("Wisteria 1.20.1 synchronous FG verification failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Wisteria 1.20.1 synchronous FG source and package contracts: verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
