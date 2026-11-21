package com.onlineskins.mod.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carries a player's files across the mod's rename.
 * <p>
 * The mod's id changed, and every file it reads is named after that id, so a player who updates
 * would otherwise start with no settings and an empty library: their old file is still there, just
 * under the name the mod no longer looks for. The old file is copied once, to the name it reads
 * now, and left in place so a rollback finds it again.
 */
public final class LegacyConfigAdoption {
    private LegacyConfigAdoption() {
    }

    /** Copies {@code legacyName} to {@code currentName} in {@code directory} when only the former exists. */
    public static void adopt(Path directory, String legacyName, String currentName) {
        if (directory == null) {
            return;
        }
        Path legacy = directory.resolve(legacyName);
        Path current = directory.resolve(currentName);
        try {
            if (Files.notExists(current) && Files.isRegularFile(legacy)) {
                Files.copy(legacy, current);
                QuickSkinInfo.LOGGER.info("Adopted the previous config {} as {}", legacyName, currentName);
            }
        } catch (IOException failure) {
            QuickSkinInfo.LOGGER.warn("Could not adopt the previous config {}", legacyName, failure);
        }
    }
}
