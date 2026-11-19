package com.quickskin.mod.client.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The public Crafty.gg skin catalog.
 * <p>
 * Its list endpoint pages with {@code limit} and {@code page}, and sorts by a column and
 * direction: {@code players_count,desc} is the site's default order, {@code created_at,desc} its
 * "newest". A listed skin carries only its 64-character texture hash; the PNG itself comes from
 * the single-skin endpoint as base64.
 */
@Environment(EnvType.CLIENT)
public final class CraftyCatalogService {
    private static final CraftyCatalogService INSTANCE = new CraftyCatalogService();

    private static final String LIST_BASE = "https://api.crafty.gg/api/v2/skins";
    private static final String TRENDING_BASE = "https://api.crafty.gg/api/v2/skins/trending";
    private static final String SKIN_BASE = "https://api.crafty.gg/api/v2/skins/";
    /** Marks a reference as Crafty's, so a tile knows which service resolves it. */
    public static final String REF_PREFIX = "crafty:";
    private static final int PAGE_SIZE = 20;
    /** The trending endpoint ignores limit and page, and answers a single page of ten entries. */
    private static final int TRENDING_PAGE_SIZE = 10;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    /** The orders the site's browse pages expose. */
    public enum Sort {
        /** "Most Players": the catalog page's default order. */
        MOST_WORN("players_count,desc"),
        /** "New Skins". */
        NEWEST("created_at,desc"),
        /** "Most Viewed". */
        TOP_VIEWED("views_monthly,desc"),
        /** "Trending", a separate endpoint with its own page size. */
        TRENDING(null);

        private final String order;

        Sort(String order) {
            this.order = order;
        }
    }

    public static CraftyCatalogService getInstance() {
        return INSTANCE;
    }

    /**
     * Fetch one page of the catalog, as the shared page type the catalog screen already draws.
     */
    public CompletableFuture<ElyByCatalogService.CatalogPage> fetchPage(int page, Sort sort) {
        int requested = Math.max(1, page);
        Sort order = sort == null ? Sort.MOST_WORN : sort;
        String url = order == Sort.TRENDING
                ? TRENDING_BASE + "?sort=increase&days=7&limit=" + TRENDING_PAGE_SIZE + "&page=" + requested
                : LIST_BASE + "?limit=" + PAGE_SIZE + "&page=" + requested + "&type=skin&sort=" + order.order;
        return ClientIoExecutor.supplyAsync(() -> {
            byte[] body = read(url, MAX_JSON_BYTES);
            try {
                JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonElement rowsElement = root.get("data");
                if (rowsElement == null || !rowsElement.isJsonArray()) {
                    throw new ElyByCatalogService.CatalogException("Malformed Crafty catalog response", null);
                }
                JsonArray rows = rowsElement.getAsJsonArray();
                List<ElyByCatalogService.CatalogItem> items = new ArrayList<>(rows.size());
                for (JsonElement row : rows) {
                    if (!row.isJsonObject()) continue;
                    JsonObject object = row.getAsJsonObject();
                    try {
                        String hash = string(object, "hash");
                        if (!hash.matches("[0-9a-fA-F]{64}")) continue;
                        // The screen treats every item as a texture reference it can download
                        items.add(new ElyByCatalogService.CatalogItem(hash.hashCode() & 0xFFFFFFFFL,
                                REF_PREFIX + hash, booleanOrFalse(object, "slim"),
                                firstPlayer(object), intOr(object, "players_count", 0)));
                    } catch (Exception skipped) {
                        // A malformed row must not abort the page.
                    }
                }
                JsonObject meta = root.has("meta") && root.get("meta").isJsonObject()
                        ? root.getAsJsonObject("meta") : new JsonObject();
                int total = intOr(meta, "total", items.size());
                int lastPage;
                if (order == Sort.TRENDING) {
                    // This endpoint ignores page and always answers the same ten entries.
                    lastPage = requested;
                } else {
                    // The service caps its own paging: twenty entries per page, twenty-five pages.
                    lastPage = Math.max(requested, intOr(meta, "last_page", requested));
                }
                return new ElyByCatalogService.CatalogPage(items, requested, lastPage, total);
            } catch (ElyByCatalogService.CatalogException failure) {
                throw failure;
            } catch (RuntimeException malformed) {
                throw new ElyByCatalogService.CatalogException("Malformed Crafty catalog response", malformed);
            }
        });
    }

    /** Resolve a {@code crafty:<hash>} reference to the skin's PNG bytes, from cache when possible. */
    public CompletableFuture<byte[]> download(String reference) {
        return ClientIoExecutor.supplyAsync(() -> {
            String hash = reference.startsWith(REF_PREFIX) ? reference.substring(REF_PREFIX.length()) : reference;
            Path cached = cacheFile(reference);
            if (cached != null && Files.isRegularFile(cached)) {
                try {
                    return Files.readAllBytes(cached);
                } catch (IOException unreadable) {
                    // Fall through to the network below.
                }
            }
            if (!hash.matches("[0-9a-fA-F]{64}")) {
                throw new ElyByCatalogService.CatalogException("Untrusted Crafty texture reference", null);
            }
            byte[] body = read(SKIN_BASE + hash, MAX_JSON_BYTES);
            byte[] png;
            try {
                JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonObject data = root.has("data") && root.get("data").isJsonObject()
                        ? root.getAsJsonObject("data") : null;
                if (data == null) throw new IllegalArgumentException("Missing data");
                png = Base64.getDecoder().decode(string(data, "texture"));
            } catch (RuntimeException malformed) {
                throw new ElyByCatalogService.CatalogException("Malformed Crafty skin response", malformed);
            }
            if (png.length > MAX_SKIN_BYTES) {
                throw new ElyByCatalogService.CatalogException("Crafty skin too large", null);
            }
            if (cached != null) {
                try {
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, png);
                } catch (IOException unwritable) {
                    // A cache that cannot be written only costs a download next time.
                }
            }
            return png;
        });
    }

    /** The cached PNG for a reference, sharing the folder the Ely.by source already uses. */
    private static Path cacheFile(String reference) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(reference.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(40);
            for (byte value : digest) {
                name.append(Character.forDigit((value >> 4) & 0xF, 16));
                name.append(Character.forDigit(value & 0xF, 16));
            }
            return PlatformHelper.getGameDirectory()
                    .resolve("quickskin").resolve("elyby-cache").resolve(name + ".png");
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string: " + name);
        }
        return value.getAsString();
    }

    private static boolean booleanOrFalse(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static int intOr(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return value.getAsInt();
    }

    private static String firstPlayer(JsonObject object) {
        JsonElement player = object.get("first_player");
        if (player != null && player.isJsonObject()) {
            JsonElement name = player.getAsJsonObject().get("username");
            if (name != null && name.isJsonPrimitive()) return name.getAsString();
        }
        return "";
    }

    private static byte[] read(String url, int maximum) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "QuickSkin");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new ElyByCatalogService.CatalogException("Crafty answered HTTP " + status, null);
            }
            long advertised = connection.getContentLengthLong();
            if (advertised > maximum) {
                throw new ElyByCatalogService.CatalogException("Crafty response too large", null);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximum + 1);
                if (body.length > maximum) {
                    throw new ElyByCatalogService.CatalogException("Crafty response too large", null);
                }
                return body;
            }
        } catch (ElyByCatalogService.CatalogException failure) {
            throw failure;
        } catch (IOException network) {
            throw new ElyByCatalogService.CatalogException("Crafty request failed", network);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
