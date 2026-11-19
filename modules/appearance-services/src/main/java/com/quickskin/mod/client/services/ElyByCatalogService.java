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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The public Ely.by skin catalog, which backs the "skins" page of ely.by.
 * <p>
 * The catalog answers 40 entries per page and caps the inventory at 10000 entries, so the last page
 * is 250. Filter and sort parameters on the page are applied by the website's own server-side
 * rendering; this endpoint ignores everything but {@code page}, so the browser offers paging only.
 */
@Environment(EnvType.CLIENT)
public final class ElyByCatalogService {
    private static final ElyByCatalogService INSTANCE = new ElyByCatalogService();

    private static final String CATALOG_BASE = "https://ely.by/api/legacy/skins";
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final ConnectionFactory connections;

    public ElyByCatalogService() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    ElyByCatalogService(ConnectionFactory connections) {
        this.connections = connections;
    }

    public static ElyByCatalogService getInstance() {
        return INSTANCE;
    }

    /** One catalog entry: a skin's storage URL, its arm model, its first tag, and how many wear it. */
    public static final class CatalogItem {
        public final long id;
        public final String skinUrl;
        public final boolean slim;
        public final String firstTag;
        /** How many players wear this skin, which is the catalog's popularity measure. */
        public final int wearers;

        CatalogItem(long id, String skinUrl, boolean slim, String firstTag, int wearers) {
            this.id = id;
            this.skinUrl = skinUrl;
            this.slim = slim;
            this.firstTag = firstTag;
            this.wearers = wearers;
        }
    }

    /** One page of the catalog, with the paging numbers the endpoint reports. */
    public static final class CatalogPage {
        public final List<CatalogItem> items;
        public final int page;
        public final int lastPage;
        public final int totalItems;

        CatalogPage(List<CatalogItem> items, int page, int lastPage, int totalItems) {
            this.items = items;
            this.page = page;
            this.lastPage = lastPage;
            this.totalItems = totalItems;
        }
    }

    /** A catalog request or download that failed; the browser shows one generic message. */
    public static final class CatalogException extends RuntimeException {
        CatalogException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public CompletableFuture<CatalogPage> fetchPage(int page) {
        return fetchPage(page, false);
    }

    /**
     * Fetch one catalog page. {@code byTime} asks for the newest uploads first; without it the
     * catalog answers in its own order, which is by wearers. This is the same {@code sort} the
     * website's own skins page uses.
     */
    public CompletableFuture<CatalogPage> fetchPage(int page, boolean byTime) {
        int requested = Math.max(1, page);
        return ClientIoExecutor.supplyAsync(() -> {
            byte[] body = read(CATALOG_BASE + "?page=" + requested + (byTime ? "&sort=time" : ""),
                    MAX_JSON_BYTES);
            try {
                JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                JsonElement rowsElement = root.get("items");
                if (rowsElement == null || !rowsElement.isJsonArray()) {
                    throw new CatalogException("Malformed Ely.by catalog response", null);
                }
                JsonArray rows = rowsElement.getAsJsonArray();
                List<CatalogItem> items = new ArrayList<>(rows.size());
                for (JsonElement row : rows) {
                    if (!row.isJsonObject()) continue;
                    JsonObject object = row.getAsJsonObject();
                    // A malformed row must not abort the page: skip it and show the rest.
                    try {
                        URL url = MojangApiService.validateTextureUrl(string(object, "skin_url"));
                        items.add(new CatalogItem(object.get("id").getAsLong(), url.toString(),
                                booleanOrFalse(object, "is_slim"), firstTag(object),
                                intOr(object, "count_wearers", 0)));
                    } catch (Exception skipped) {
                        // Ignore this row.
                    }
                }
                return new CatalogPage(items, intOr(root, "current", requested),
                        intOr(root, "last", requested), intOr(root, "total_items", items.size()));
            } catch (CatalogException failure) {
                throw failure;
            } catch (RuntimeException malformed) {
                throw new CatalogException("Malformed Ely.by catalog response", malformed);
            }
        });
    }

    /** Download a catalog skin as its original PNG bytes, from the local cache when possible. */
    public CompletableFuture<byte[]> download(String skinUrl) {
        return ClientIoExecutor.supplyAsync(() -> {
            Path cached = cacheFile(String.valueOf(skinUrl));
            if (cached != null && Files.isRegularFile(cached)) {
                try {
                    return Files.readAllBytes(cached);
                } catch (IOException unreadable) {
                    // Fall through to the network below.
                }
            }
            URL url;
            try {
                url = MojangApiService.validateTextureUrl(skinUrl);
            } catch (Exception malformed) {
                throw new CatalogException("Untrusted Ely.by skin URL", malformed);
            }
            byte[] body = read(url.toString(), MAX_SKIN_BYTES);
            if (cached != null) {
                try {
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, body);
                } catch (IOException unwritable) {
                    // A cache that cannot be written only costs a download next time.
                }
            }
            return body;
        });
    }

    /** The cached PNG for a texture URL, inside the instance's own quickskin folder. */
    private static Path cacheFile(String skinUrl) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(skinUrl.getBytes(StandardCharsets.UTF_8));
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

    private static String firstTag(JsonObject object) {
        JsonElement tags = object.get("tags");
        if (tags == null || !tags.isJsonArray() || tags.getAsJsonArray().isEmpty()) return "";
        JsonElement first = tags.getAsJsonArray().get(0);
        return first.isJsonPrimitive() && first.getAsJsonPrimitive().isString() ? first.getAsString() : "";
    }

    private byte[] read(String url, int maximum) {
        HttpURLConnection connection = null;
        try {
            connection = connections.open(new URL(url));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json,image/png");
            connection.setRequestProperty("User-Agent", "QuickSkin");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new CatalogException("Ely.by answered HTTP " + status, null);
            }
            long advertised = connection.getContentLengthLong();
            if (advertised > maximum) {
                throw new CatalogException("Ely.by response too large", null);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximum + 1);
                if (body.length > maximum) {
                    throw new CatalogException("Ely.by response too large", null);
                }
                return body;
            }
        } catch (CatalogException failure) {
            throw failure;
        } catch (IOException network) {
            throw new CatalogException("Ely.by request failed", network);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
