package com.onlineskins.mod.client.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CatalogCacheTest {

    @TempDir
    Path directory;

    @Test
    void writeReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
        Path file = directory.resolve("sheet.png");
        byte[] first = png(1);
        byte[] second = png(2);

        CatalogCache.write(file, first);
        CatalogCache.write(file, second);

        assertArrayEquals(second, Files.readAllBytes(file));
        assertEquals(List.of("sheet.png"), names(directory),
                "the atomic replacement must not leave its temporary file behind");
    }

    @Test
    void damagedCacheFileIsDeletedRatherThanServed() throws IOException {
        Path file = directory.resolve("sheet.png");
        Files.write(file, "not a png".getBytes(StandardCharsets.US_ASCII));

        assertNull(CatalogCache.read(file));
        assertFalse(Files.exists(file), "a damaged file is removed so the next download can replace it");
    }

    @Test
    void oversizedCacheFileIsDeletedRatherThanRead() throws IOException {
        Path file = directory.resolve("sheet.png");
        byte[] huge = new byte[(int) CatalogCache.MAX_FILE_BYTES + 1];
        System.arraycopy(png(1), 0, huge, 0, 8);
        Files.write(file, huge);

        assertNull(CatalogCache.read(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void onlyPngBytesPassTheSignatureCheck() {
        assertTrue(CatalogCache.isPng(png(4)));
        assertFalse(CatalogCache.isPng(new byte[0]));
        assertFalse(CatalogCache.isPng("GIF89a".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void pruneKeepsTheNewestFilesWithinTheCount() throws IOException {
        for (int index = 0; index < 5; index++) {
            Path file = directory.resolve("sheet-" + index + ".png");
            Files.write(file, png(index));
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L - index * 1_000L));
        }

        CatalogCache.prune(directory, 2, CatalogCache.MAX_BYTES);

        assertEquals(List.of("sheet-0.png", "sheet-1.png"), names(directory),
                "the oldest files go first");
    }

    @Test
    void pruneKeepsTheNewestFilesWithinTheByteBudget() throws IOException {
        for (int index = 0; index < 3; index++) {
            Path file = directory.resolve("sheet-" + index + ".png");
            Files.write(file, new byte[8]);
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L - index * 1_000L));
        }

        CatalogCache.prune(directory, 100, 16L);

        assertEquals(List.of("sheet-0.png", "sheet-1.png"), names(directory));
    }

    private static byte[] png(int filler) {
        byte[] bytes = new byte[16];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        for (int index = signature.length; index < bytes.length; index++) {
            bytes[index] = (byte) filler;
        }
        return bytes;
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }
}
