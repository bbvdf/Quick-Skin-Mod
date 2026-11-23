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
import java.util.regex.Pattern;

/**
 * The public MineSkin upload catalog.
 * <p>
 * Its listing endpoint pages with {@code page} and {@code size}; {@code size} is capped at 64, so
 * a logical page asks for 60 entries — a number every grid width the screen offers divides evenly.
 * Each row carries only the texture's URL on {@code textures.minecraft.net}, which is what a tile
 * downloads. The arm model is decided when the PNG arrives, like a fresh import.
 * <p>
 * The endpoint answers with a {@code _deprecated} note pointing at the list-by-reference call,
 * which takes known UUIDs and cannot page a catalog; this one is kept until it stops answering.
 */
@Environment(EnvType.CLIENT)
public final class MineSkinCatalogService {
    private static final MineSkinCatalogService INSTANCE = new MineSkinCatalogService();

    private static final String LIST_BASE = "https://api.mineskin.org/get/list/";
    private static final String TEXTURE_BASE = "https://textures.minecraft.net/texture/";
    /** Marks a reference as MineSkin's, so a tile knows which service resolves it. */
    public static final String REF_PREFIX = "mineskin:";
    /** The endpoint caps a page at 64; 60 keeps one page divisible by every grid width used. */
    private static final int PAGE_SIZE = 60;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    private static final Pattern TEXTURE_HASH = Pattern.compile("[0-9a-fA-F]{20,64}");

    public static MineSkinCatalogService getInstance() {
        return INSTANCE;
    }

    /** Fetch one page of the catalog, as the shared page type the screen draws. */
    public CompletableFuture<ElyByCatalogService.CatalogPage> fetchPage(int page) {
        int requested = Math.max(1, page);
        return ClientIoExecutor.supplyAsync(() -> readPage(requested));
    }

    private ElyByCatalogService.CatalogPage readPage(int page) {
        JsonObject root = fetchJson(LIST_BASE + page + "?size=" + PAGE_SIZE);
        JsonElement rowsElement = root.get("skins");
        if (rowsElement == null || !rowsElement.isJsonArray()) {
            throw new ElyByCatalogService.CatalogException("Malformed MineSkin catalog response", null);
        }
        JsonArray rows = rowsElement.getAsJsonArray();
        List<ElyByCatalogService.CatalogItem> items = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) {
                continue;
            }
            JsonObject object = row.getAsJsonObject();
            String hash = textureHash(object);
            // One texture can be uploaded more than once; the hash is what a tile may show twice.
            if (hash == null || !seen.add(hash)) {
                continue;
            }
            items.add(new ElyByCatalogService.CatalogItem(
                    entryId(object, hash), REF_PREFIX + hash, false, "", 0));
        }
        // The endpoint reports no total; a short page is the last one.
        int lastPage = rows.size() < PAGE_SIZE ? page : page + 1;
        return new ElyByCatalogService.CatalogPage(items, page, lastPage, items.size());
    }

    /** The texture hash a row points at, or null when the row has no usable URL. */
    private static String textureHash(JsonObject row) {
        JsonElement url = row.get("url");
        if (url == null || !url.isJsonPrimitive() || !url.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = url.getAsString();
        int slash = text.lastIndexOf('/');
        String hash = slash >= 0 ? text.substring(slash + 1) : text;
        return TEXTURE_HASH.matcher(hash).matches() ? hash.toLowerCase(Locale.ROOT) : null;
    }

    /** The row's own numeric id, which is unique across pages; the hash stands in if it is absent. */
    private static long entryId(JsonObject row, String hash) {
        JsonElement id = row.get("id");
        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
            return id.getAsLong();
        }
        return hash.hashCode() & 0xFFFFFFFFL;
    }

    private static JsonObject fetchJson(String url) {
        byte[] body = read(url, MAX_JSON_BYTES, "application/json");
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new ElyByCatalogService.CatalogException("Malformed MineSkin catalog response", malformed);
        }
    }

    /** Resolve a {@code mineskin:<hash>} reference to the skin's PNG bytes, from cache when possible. */
    public CompletableFuture<byte[]> download(String reference) {
        return ClientIoExecutor.supplyAsync(() -> {
            String hash = reference.startsWith(REF_PREFIX) ? reference.substring(REF_PREFIX.length()) : reference;
            Path cached = CatalogCache.fileFor(reference);
            byte[] ready = CatalogCache.read(cached);
            if (ready != null) {
                return ready;
            }
            if (!TEXTURE_HASH.matcher(hash).matches()) {
                throw new ElyByCatalogService.CatalogException("Untrusted MineSkin texture reference", null);
            }
            byte[] png = read(TEXTURE_BASE + hash, MAX_SKIN_BYTES, "image/png");
            if (!CatalogCache.isPng(png)) {
                throw new ElyByCatalogService.CatalogException("MineSkin answered something that is not a PNG", null);
            }
            CatalogCache.write(cached, png);
            return png;
        });
    }

    private static byte[] read(String url, int maximum, String accept) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "QuickSkin");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new ElyByCatalogService.CatalogException("MineSkin answered HTTP " + status, null);
            }
            long advertised = connection.getContentLengthLong();
            if (advertised > maximum) {
                throw new ElyByCatalogService.CatalogException("MineSkin response too large", null);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximum + 1);
                if (body.length > maximum) {
                    throw new ElyByCatalogService.CatalogException("MineSkin response too large", null);
                }
                return body;
            }
        } catch (ElyByCatalogService.CatalogException failure) {
            throw failure;
        } catch (IOException network) {
            throw new ElyByCatalogService.CatalogException("MineSkin request failed", network);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
