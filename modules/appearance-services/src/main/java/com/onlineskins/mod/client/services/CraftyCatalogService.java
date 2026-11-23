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
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Base for every Crafty API call. Defaults to the CF/Vercel relay because crafty.gg returns
     * 403 to mainland-China IPs; pass {@code -Donlineskins.craftyBase=https://api.crafty.gg}
     * to talk to the origin directly.
     */
    private static final String CRAFTY_BASE =
            System.getProperty("onlineskins.craftyBase", "https://crafty.bbvdf.cc.cd/c");
    private static final String LIST_BASE = CRAFTY_BASE + "/api/v2/skins";
    private static final String TRENDING_BASE = CRAFTY_BASE + "/api/v2/skins/trending";
    private static final String SKIN_BASE = CRAFTY_BASE + "/api/v2/skins/";
    /** Marks a reference as Crafty's, so a tile knows which service resolves it. */
    public static final String REF_PREFIX = "crafty:";
    private static final int PAGE_SIZE = 20;
    /** The trending endpoint caps a page at ten entries however large a limit is asked for. */
    private static final int TRENDING_PAGE_SIZE = 10;
    /** Trending pages read per grid page, so a grid page still fills a screen of tiles. */
    private static final int TRENDING_PAGES_PER_SCREEN = 2;
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
        /** Trending: "Biggest gainers", the biggest absolute growth in the window. */
        TRENDING_GAINERS("increase"),
        /** Trending: "Fastest growing", the biggest percentage growth in the window. */
        TRENDING_GROWING("increase_percentage"),
        /** Trending: "Hot right now", the most players wearing it now. */
        TRENDING_HOT("current_players");

        private final String order;

        Sort(String order) {
            this.order = order;
        }

        boolean isTrending() {
            return order.equals("increase") || order.equals("increase_percentage")
                    || order.equals("current_players");
        }
    }

    public static CraftyCatalogService getInstance() {
        return INSTANCE;
    }

    /**
     * Fetch one page of the catalog, as the shared page type the catalog screen already draws.
     * <p>
     * A trending page holds only ten entries while a screen of the grid holds more, so trending
     * reads two service pages per screen; every other order is a single request.
     */
    public CompletableFuture<ElyByCatalogService.CatalogPage> fetchPage(int page, Sort sort, int windowDays) {
        int requested = Math.max(1, page);
        Sort order = sort == null ? Sort.MOST_WORN : sort;
        int days = Math.max(1, windowDays);
        if (order.isTrending()) {
            return ClientIoExecutor.supplyAsync(() -> readTrending(requested, order, days));
        }
        String url = LIST_BASE + "?limit=" + PAGE_SIZE + "&page=" + requested
                + "&type=skin&sort=" + order.order;
        return ClientIoExecutor.supplyAsync(() -> {
            try {
                JsonObject root = fetchJson(url);
                List<ElyByCatalogService.CatalogItem> items = parseRows(root.get("data"));
                JsonObject meta = metaOf(root);
                int total = intOr(meta, "total", items.size());
                // The service caps its own paging: twenty entries per page, twenty-five pages.
                int lastPage = Math.max(requested, intOr(meta, "last_page", requested));
                return new ElyByCatalogService.CatalogPage(items, requested, lastPage, total);
            } catch (ElyByCatalogService.CatalogException failure) {
                throw failure;
            } catch (RuntimeException malformed) {
                throw new ElyByCatalogService.CatalogException("Malformed Crafty catalog response", malformed);
            }
        });
    }

    /** Read enough trending pages to fill a screen of the grid, and report whether more remain. */
    private ElyByCatalogService.CatalogPage readTrending(int logicalPage, Sort order, int days) {
        int first = (logicalPage - 1) * TRENDING_PAGES_PER_SCREEN + 1;
        List<ElyByCatalogService.CatalogItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean hasMore = false;
        for (int page = first; page < first + TRENDING_PAGES_PER_SCREEN; page++) {
            try {
                JsonObject root = fetchJson(TRENDING_BASE + "?sort=" + order.order + "&days=" + days
                        + "&limit=" + TRENDING_PAGE_SIZE + "&page=" + page);
                for (ElyByCatalogService.CatalogItem item : parseRows(root.get("data"))) {
                    if (seen.add(item.skinUrl)) {
                        items.add(item);
                    }
                }
                JsonObject meta = metaOf(root);
                hasMore = meta.has("hasMore") && meta.get("hasMore").isJsonPrimitive()
                        && meta.get("hasMore").getAsBoolean();
                if (!hasMore) {
                    break;
                }
            } catch (ElyByCatalogService.CatalogException failure) {
                if (items.isEmpty()) throw failure;
                break;
            } catch (RuntimeException malformed) {
                if (items.isEmpty()) {
                    throw new ElyByCatalogService.CatalogException("Malformed Crafty catalog response", malformed);
                }
                break;
            }
        }
        return new ElyByCatalogService.CatalogPage(items, logicalPage,
                hasMore ? logicalPage + 1 : logicalPage, items.size());
    }

    private static JsonObject fetchJson(String url) {
        byte[] body = read(url, MAX_JSON_BYTES);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** The data rows of a response, skipping any row that cannot be read. */
    private static List<ElyByCatalogService.CatalogItem> parseRows(JsonElement rowsElement) {
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
                // The screen treats every item as a texture reference it can download.
                items.add(new ElyByCatalogService.CatalogItem(catalogId(hash),
                        REF_PREFIX + hash, booleanOrFalse(object, "slim"),
                        firstPlayer(object), intOr(object, "players_count", 0)));
            } catch (Exception skipped) {
                // A malformed row must not abort the page.
            }
        }
        return items;
    }

    /**
     * The catalog id of a texture hash: the first sixteen hex digits of the 64-character hash,
     * read as one unsigned 64-bit value. The whole hash is the site's identity, so truncating it
     * to a 32-bit string hash would let two different skins share one grid entry.
     */
    static long catalogId(String hash) {
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    private static JsonObject metaOf(JsonObject root) {
        return root.has("meta") && root.get("meta").isJsonObject()
                ? root.getAsJsonObject("meta") : new JsonObject();
    }

    /** Resolve a {@code crafty:<hash>} reference to the skin's PNG bytes, from cache when possible. */
    public CompletableFuture<byte[]> download(String reference) {
        return ClientIoExecutor.supplyAsync(() -> {
            String hash = reference.startsWith(REF_PREFIX) ? reference.substring(REF_PREFIX.length()) : reference;
            Path cached = CatalogCache.fileFor(reference);
            byte[] ready = CatalogCache.read(cached);
            if (ready != null) {
                return ready;
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
            if (!CatalogCache.isPng(png)) {
                throw new ElyByCatalogService.CatalogException("Crafty answered something that is not a PNG", null);
            }
            CatalogCache.write(cached, png);
            return png;
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
