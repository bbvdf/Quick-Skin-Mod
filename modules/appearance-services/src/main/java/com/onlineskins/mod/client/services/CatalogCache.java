package com.onlineskins.mod.client.services;

import com.onlineskins.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The PNG cache the public catalogs share.
 * <p>
 * Every catalog key is hashed into one file name inside the instance's own
 * {@code onlineskins/elyby-cache} folder. Files are replaced atomically so a reader never sees
 * half a PNG, a file that no longer starts like a PNG is deleted rather than decoded, and the
 * folder stays inside a fixed file-count and byte budget so a long browsing session cannot grow
 * without bound.
 */
final class CatalogCache {
    /** Files kept in the shared folder; the smallest sensible browse history. */
    static final int MAX_FILES = 500;
    /** Total bytes kept in the shared folder. */
    static final long MAX_BYTES = 32L * 1024L * 1024L;
    /** A skin larger than this is not a sheet any catalog publishes. */
    static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    /** Set only by tests, which cannot bind the loader's game directory. */
    private static volatile Path directoryOverride;

    private CatalogCache() {
    }

    /** The shared cache folder, or null while the loader has not bound the game directory. */
    static Path directory() {
        Path override = directoryOverride;
        if (override != null) {
            return override;
        }
        try {
            return PlatformHelper.getGameDirectory().resolve("onlineskins").resolve("elyby-cache");
        } catch (Throwable unbound) {
            // @ExpectPlatform throws an AssertionError until the assembled JAR is transformed.
            return null;
        }
    }

    /** Points the cache at a temporary folder; tests only. */
    static void useDirectoryForTesting(Path directory) {
        directoryOverride = directory;
    }

    /** The PNG holding one catalog reference, named by the SHA-1 of that reference. */
    static Path fileFor(String reference) {
        Path directory = directory();
        if (directory == null || reference == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(reference.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(40);
            for (byte value : digest) {
                name.append(Character.forDigit((value >> 4) & 0xF, 16));
                name.append(Character.forDigit(value & 0xF, 16));
            }
            return directory.resolve(name + ".png");
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** Whether these bytes start like a PNG, which is all a cached catalog sheet can be. */
    static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The bytes of a cached PNG, or null when the file is missing, implausibly sized, or damaged.
     * A damaged file is deleted here so the caller's retry writes a clean replacement.
     */
    static byte[] read(Path file) {
        if (file == null) {
            return null;
        }
        try {
            long size = Files.size(file);
            if (size < PNG_SIGNATURE.length || size > MAX_FILE_BYTES) {
                Files.deleteIfExists(file);
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (!isPng(bytes)) {
                Files.deleteIfExists(file);
                return null;
            }
            return bytes;
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Writes a cache file in one move, so a concurrent reader sees the old or the new file. */
    static void write(Path file, byte[] bytes) {
        if (file == null || bytes == null) {
            return;
        }
        Path directory = file.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException unwritable) {
            // A cache that cannot be written only costs a download next time.
        } finally {
            delete(temporary);
        }
        prune(directory);
    }

    /** Drops the oldest files until the folder is back inside its count and byte budget. */
    static void prune(Path directory) {
        prune(directory, MAX_FILES, MAX_BYTES);
    }

    static void prune(Path directory, int maximumFiles, long maximumBytes) {
        if (directory == null) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        long total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.png")) {
            for (Path entry : stream) {
                try {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    entries.add(entry);
                    total += Files.size(entry);
                } catch (IOException vanished) {
                    // A file that is already gone costs nothing.
                }
            }
        } catch (IOException unavailable) {
            return;
        }
        if (entries.size() <= maximumFiles && total <= maximumBytes) {
            return;
        }
        entries.sort(Comparator.comparingLong(CatalogCache::lastModified));
        int count = entries.size();
        for (Path entry : entries) {
            if (count <= maximumFiles && total <= maximumBytes) {
                break;
            }
            try {
                long size = Files.size(entry);
                if (Files.deleteIfExists(entry)) {
                    count--;
                    total -= size;
                }
            } catch (IOException undeletable) {
                // An entry that stays only tightens the next prune.
            }
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unavailable) {
            return Long.MAX_VALUE;
        }
    }

    private static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temporary file is pruned with the rest of the folder.
        }
    }
}
