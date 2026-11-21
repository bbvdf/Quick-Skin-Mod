package com.onlineskins.mod.client.services;

import com.onlineskins.mod.client.concurrent.ClientIoExecutor;
import com.onlineskins.mod.platform.PlatformHelper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The public mcskins.top catalog, which backs the site's 128x128 pages.
 * <p>
 * The site has no JSON API: every listing is server-rendered HTML, one page holding twenty-four
 * cards whose only machine-readable part is the {@code /skin/<slug>} link. The skin itself is a
 * plain PNG at {@code /assets/images/skin/<slug>.png}, so a card costs one HTML read and one PNG.
 * <p>
 * The three entries the screen offers are the site's own windows: this month's uploads, this
 * week's uploads, and the whole 128x128 inventory in the site's own (shuffled) order. Paging past
 * the last page of a window is answered with page one again rather than an error, so a page that
 * falls short of twenty-four cards, or that repeats the page before it, is the last one.
 */
@Environment(EnvType.CLIENT)
public final class McSkinsCatalogService {
    private static final McSkinsCatalogService INSTANCE = new McSkinsCatalogService();

    private static final String SITE_BASE = "https://mcskins.top/";
    private static final String SKIN_BASE = SITE_BASE + "assets/images/skin/";
    /** Marks a reference as mcskins.top's, so a tile knows which service resolves it. */
    public static final String REF_PREFIX = "mcskins:";

    /** Cards on one page of the site; a shorter page is the window's last. */
    private static final int SITE_PAGE_SIZE = 24;
    /**
     * Site pages merged into one logical page. 120 cards divide evenly by the tile counts a screen
     * can hold (8, 10, 12, 15, 20, 24), so no page leaves a half-empty row behind.
     */
    private static final int SITE_PAGES_PER_LOGICAL_PAGE = 5;

    private static final int MAX_PAGE_BYTES = 256 * 1024;
    private static final int MAX_SKIN_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    private static final Pattern SKIN_LINK = Pattern.compile("href=\"/skin/([A-Za-z0-9_\\-]+)\"");
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_\\-]{1,80}");

    /** The site's three 128x128 entries, in the order the screen lists them. */
    public enum Window {
        /** Uploads of this month, the entry the screen opens on. */
        MONTH("128x128/top/month/"),
        /** Uploads of this week. */
        WEEK("128x128/top/week/"),
        /** The whole 128x128 inventory, in the site's own order. */
        ALL("128x128/");

        private final String path;

        Window(String path) {
            this.path = path;
        }

        private String pageUrl(int page) {
            return SITE_BASE + path + (page > 1 ? "page/" + page : "");
        }
    }

    public static McSkinsCatalogService getInstance() {
        return INSTANCE;
    }

    /** Fetch one logical page of the chosen window, as the shared page type the screen draws. */
    public CompletableFuture<ElyByCatalogService.CatalogPage> fetchPage(int page, Window window) {
        int requested = Math.max(1, page);
        Window chosen = window == null ? Window.MONTH : window;
        return ClientIoExecutor.supplyAsync(() -> readPages(requested, chosen));
    }

    /**
     * Read the site pages that make up one logical page. Each card carries only a slug, so the arm
     * model is decided when its PNG arrives; the entry starts as classic, like a fresh import.
     */
    private ElyByCatalogService.CatalogPage readPages(int logicalPage, Window window) {
        int first = (logicalPage - 1) * SITE_PAGES_PER_LOGICAL_PAGE + 1;
        List<ElyByCatalogService.CatalogItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> previous = null;
        boolean reachedEnd = false;
        for (int offset = 0; offset < SITE_PAGES_PER_LOGICAL_PAGE; offset++) {
            List<String> slugs;
            try {
                slugs = slugsOf(window.pageUrl(first + offset));
            } catch (ElyByCatalogService.CatalogException failure) {
                if (items.isEmpty()) throw failure;
                break;
            }
            if (slugs.isEmpty()) {
                reachedEnd = true;
                break;
            }
            // Past its last page the site answers with page one again, so a repeat means the end.
            if (slugs.equals(previous)) {
                reachedEnd = true;
                break;
            }
            previous = slugs;
            for (String slug : slugs) {
                if (seen.add(slug)) {
                    items.add(new ElyByCatalogService.CatalogItem(
                            slug.hashCode() & 0xFFFFFFFFL, REF_PREFIX + slug, false, "", 0));
                }
            }
            if (slugs.size() < SITE_PAGE_SIZE) {
                reachedEnd = true;
                break;
            }
        }
        return new ElyByCatalogService.CatalogPage(items, logicalPage,
                reachedEnd ? logicalPage : logicalPage + 1, items.size());
    }

    /** The card slugs on one listing page, in page order and without repeats. */
    private static List<String> slugsOf(String url) {
        String html = new String(read(url, MAX_PAGE_BYTES), StandardCharsets.UTF_8);
        List<String> slugs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher matcher = SKIN_LINK.matcher(html);
        while (matcher.find()) {
            String slug = matcher.group(1);
            if (seen.add(slug)) {
                slugs.add(slug);
            }
        }
        return slugs;
    }

    /** Resolve a {@code mcskins:<slug>} reference to the skin's PNG bytes, from cache when possible. */
    public CompletableFuture<byte[]> download(String reference) {
        return ClientIoExecutor.supplyAsync(() -> {
            String slug = reference.startsWith(REF_PREFIX) ? reference.substring(REF_PREFIX.length()) : reference;
            Path cached = cacheFile(reference);
            if (cached != null && Files.isRegularFile(cached)) {
                try {
                    return Files.readAllBytes(cached);
                } catch (IOException unreadable) {
                    // Fall through to the network below.
                }
            }
            if (!SLUG.matcher(slug).matches()) {
                throw new ElyByCatalogService.CatalogException("Untrusted mcskins.top texture reference", null);
            }
            byte[] png = read(SKIN_BASE + slug + ".png", MAX_SKIN_BYTES);
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

    /** The cached PNG for a reference, sharing the folder the other catalog sources use. */
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
                    .resolve("onlineskins").resolve("elyby-cache").resolve(name + ".png");
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static byte[] read(String url, int maximum) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "text/html,image/png");
            connection.setRequestProperty("User-Agent", "QuickSkin");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new ElyByCatalogService.CatalogException("mcskins.top answered HTTP " + status, null);
            }
            long advertised = connection.getContentLengthLong();
            if (advertised > maximum) {
                throw new ElyByCatalogService.CatalogException("mcskins.top response too large", null);
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximum + 1);
                if (body.length > maximum) {
                    throw new ElyByCatalogService.CatalogException("mcskins.top response too large", null);
                }
                return body;
            }
        } catch (ElyByCatalogService.CatalogException failure) {
            throw failure;
        } catch (IOException network) {
            throw new ElyByCatalogService.CatalogException("mcskins.top request failed", network);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
