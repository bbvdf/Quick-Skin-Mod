package com.onlineskins.mod.client.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.onlineskins.mod.client.concurrent.ClientIoExecutor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /** Entries the endpoint answers per page, and how many site pages one shown page is built from. */
    private static final int SITE_PAGE_SIZE = 40;
    private static final int SITE_PAGES_PER_LOGICAL_PAGE = 2;
    /**
     * Tags Ely.by publishers use for adult content. The endpoint ignores every filter parameter, so
     * the catalog is filtered here instead; an entry carrying any of these is left out. Tags are
     * matched whole and case-insensitively, so {@code Sexy} and {@code sexy} both count while a
     * longer word that merely contains one does not.
     */
    private static final Set<String> ADULT_TAGS = Set.of(
            "18+", "nsfw", "nude", "naked", "sex", "sexy", "erotic", "xxx", "onlyfans", "bdsm",
            "cum", "porn", "pornhub", "hentai", "milf", "fuck", "anal", "topless",
            "panties", "lingerie", "bikini", "bra", "boobs", "breasts",
            "vagina", "penis", "pussy", "dick", "cock",
            "\u0441\u0435\u043a\u0441\u0443\u0430\u043b\u044c\u043d\u044b\u0439", "\u0433\u043e\u043b\u0430\u044f", "\u0433\u043e\u043b\u044b\u0439");

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
            int first = (requested - 1) * SITE_PAGES_PER_LOGICAL_PAGE + 1;
            ServerPage opening = readServerPage(first, byTime);
            List<CatalogItem> items = new ArrayList<>(SITE_PAGE_SIZE);
            Set<Long> seen = new HashSet<>();
            collect(opening, items, seen);
            int last = opening.lastPage();
            int total = opening.total();
            // Adult entries are dropped after the endpoint answers, so a page is topped up from the
            // next site page instead of showing a short row of tiles.
            // The endpoint's own row count says whether another page exists; the filtered count is
            // always smaller, so comparing that would stop topping up early.
            if (items.size() < SITE_PAGE_SIZE && opening.rawCount() >= SITE_PAGE_SIZE) {
                try {
                    ServerPage next = readServerPage(first + 1, byTime);
                    last = Math.max(last, next.lastPage());
                    total = Math.max(total, next.total());
                    collect(next, items, seen);
                } catch (CatalogException halfRead) {
                    // Keep what the first page gave; the next visit retries the missing part.
                }
            }
            if (items.size() > SITE_PAGE_SIZE) {
                items = new ArrayList<>(items.subList(0, SITE_PAGE_SIZE));
            }
            return new CatalogPage(items, requested, logicalLastPage(last), total);
        });
    }

    /** One site page after the adult filter, plus its raw row count, last page and inventory size. */
    private record ServerPage(List<CatalogItem> items, int lastPage, int rawCount, int total) {
    }

    private ServerPage readServerPage(int page, boolean byTime) {
        byte[] body = read(CATALOG_BASE + "?page=" + page + (byTime ? "&sort=time" : ""),
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
                    if (isAdult(object)) continue;
                    URL url = MojangApiService.validateTextureUrl(string(object, "skin_url"));
                    items.add(new CatalogItem(object.get("id").getAsLong(), url.toString(),
                            booleanOrFalse(object, "is_slim"), firstTag(object),
                            intOr(object, "count_wearers", 0)));
                } catch (Exception skipped) {
                    // Ignore this row.
                }
            }
            return new ServerPage(items, intOr(root, "last", page), rows.size(),
                    intOr(root, "total_items", rows.size()));
        } catch (CatalogException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new CatalogException("Malformed Ely.by catalog response", malformed);
        }
    }

    private static void collect(ServerPage page, List<CatalogItem> items, Set<Long> seen) {
        for (CatalogItem item : page.items()) {
            if (seen.add(item.id)) {
                items.add(item);
            }
        }
    }

    /** The shown page number holding the site's last page, since one shown page spans two. */
    private static int logicalLastPage(int serverLastPage) {
        return Math.max(1, (serverLastPage + SITE_PAGES_PER_LOGICAL_PAGE - 1) / SITE_PAGES_PER_LOGICAL_PAGE);
    }

    /** Whether an entry carries a tag Ely.by publishers use for adult content. */
    private static boolean isAdult(JsonObject row) {
        JsonElement tags = row.get("tags");
        if (tags == null || !tags.isJsonArray()) {
            return false;
        }
        for (JsonElement tag : tags.getAsJsonArray()) {
            if (tag != null && tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()
                    && ADULT_TAGS.contains(tag.getAsString().trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Download a catalog skin as its original PNG bytes, from the local cache when possible. */
    public CompletableFuture<byte[]> download(String skinUrl) {
        return ClientIoExecutor.supplyAsync(() -> {
            Path cached = CatalogCache.fileFor(String.valueOf(skinUrl));
            byte[] ready = CatalogCache.read(cached);
            if (ready != null) {
                return ready;
            }
            URL url;
            try {
                url = MojangApiService.validateTextureUrl(skinUrl);
            } catch (Exception malformed) {
                throw new CatalogException("Untrusted Ely.by skin URL", malformed);
            }
            byte[] body = read(url.toString(), MAX_SKIN_BYTES);
            if (!CatalogCache.isPng(body)) {
                throw new CatalogException("Ely.by answered something that is not a PNG", null);
            }
            CatalogCache.write(cached, body);
            return body;
        });
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
