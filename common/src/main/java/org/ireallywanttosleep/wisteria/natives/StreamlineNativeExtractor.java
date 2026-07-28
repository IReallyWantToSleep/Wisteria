package org.ireallywanttosleep.wisteria.natives;

import org.ireallywanttosleep.wisteria.Wisteria;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks the NVIDIA Streamline runtime from the mod jar onto disk, where Super Resolution
 * can hand the directory to the interposer.
 * <p>
 * This only writes files; loading is Super Resolution's job, and it happens later, from
 * the render thread, once SR has decided it actually wants Streamline.
 */
public final class StreamlineNativeExtractor {
    private static final String RESOURCE_ROOT = "/streamline/";

    private StreamlineNativeExtractor() {
    }

    /**
     * Writes the Streamline DLLs into {@code targetDirectory}, skipping any whose size
     * already matches, and returns that directory. Returns {@code null} when the jar was
     * built without a Streamline SDK or extraction failed, which leaves Super Resolution
     * on its non-Streamline backends.
     */
    public static Path extract(Path targetDirectory) {
        List<Entry> entries = readIndex();
        if (entries.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(targetDirectory);
            for (Entry entry : entries) {
                Path target = targetDirectory.resolve(entry.name);
                if (Files.isRegularFile(target) && Files.size(target) == entry.size) {
                    continue;
                }
                try (InputStream source = open(entry.name)) {
                    if (source == null) {
                        Wisteria.LOGGER.error("Streamline library {} is listed but missing from the jar", entry.name);
                        return null;
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException failure) {
            Wisteria.LOGGER.error("Failed to extract the Streamline runtime into {}", targetDirectory, failure);
            return null;
        }
        Wisteria.LOGGER.info("Streamline runtime available at {}", targetDirectory);
        return targetDirectory;
    }

    private static List<Entry> readIndex() {
        List<Entry> entries = new ArrayList<>();
        try (InputStream index = open("index.txt")) {
            if (index == null) {
                Wisteria.LOGGER.info("No Streamline runtime in this build; Streamline backends stay unavailable");
                return List.of();
            }
            for (String line : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.lastIndexOf(' ');
                entries.add(new Entry(
                        trimmed.substring(0, separator),
                        Long.parseLong(trimmed.substring(separator + 1))
                ));
            }
        } catch (IOException | RuntimeException failure) {
            Wisteria.LOGGER.error("Failed to read the Streamline runtime index", failure);
            return List.of();
        }
        return entries;
    }

    private static InputStream open(String name) {
        return StreamlineNativeExtractor.class.getResourceAsStream(RESOURCE_ROOT + name);
    }

    private record Entry(String name, long size) {
    }
}
