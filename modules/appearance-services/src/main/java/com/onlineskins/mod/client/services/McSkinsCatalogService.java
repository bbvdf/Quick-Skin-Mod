package com.onlineskins.mod.client.services;

import com.onlineskins.mod.client.concurrent.ClientIoExecutor;
import com.onlineskins.mod.platform.QuickSkinInfo;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * Each window's first site page as it was read. The site answers a page past the end by
     * repeating page one, and a window's last page can hold a full twenty-four cards, so comparing
     * cards with the page before is not enough: a repeat of the opening page is the end too.
     */
    private final Map<Window, List<String>> openingPages = new ConcurrentHashMap<>();
    /** Reads one listing page's cards; a seam so the failure paths can be exercised. */
    @FunctionalInterface
    interface PageReader {
        List<String> read(String url);
    }

    private final PageReader pages;

    public McSkinsCatalogService() {
        this(McSkinsCatalogService::slugsOf);
    }

    McSkinsCatalogService(PageReader pages) {
        this.pages = pages;
    }

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

    /**
     * Fetch one logical page of the chosen window, as the shared page type the screen draws.
     * <p>
     * The first site page goes alone, because a window that fits in one page ends there; once it
     * comes back full, the rest of the window's pages are read at the same time rather than one
     * after another, which is most of what a page used to cost.
     */
    public CompletableFuture<ElyByCatalogService.CatalogPage> fetchPage(int page, Window window) {
        int requested = Math.max(1, page);
        Window chosen = window == null ? Window.MONTH : window;
        int first = (requested - 1) * SITE_PAGES_PER_LOGICAL_PAGE + 1;
        return readSlugs(chosen.pageUrl(first)).thenCompose(opening -> {
            if (opening.size() < SITE_PAGE_SIZE) {
                return CompletableFuture.completedFuture(
                        assemble(requested, chosen, List.of(opening), false));
            }
            List<CompletableFuture<List<String>>> rest = new ArrayList<>();
            for (int offset = 1; offset < SITE_PAGES_PER_LOGICAL_PAGE; offset++) {
                rest.add(readSlugs(chosen.pageUrl(first + offset)));
            }
            return CompletableFuture.allOf(rest.toArray(new CompletableFuture<?>[0]))
                    .handle((done, broken) -> joinPages(opening, rest, requested))
                    .thenCompose(joined -> joined.allFailed()
                            ? CompletableFuture.<ElyByCatalogService.CatalogPage>failedFuture(
                                    new ElyByCatalogService.CatalogException(
                                            "Every mcskins.top listing page failed", null))
                            : CompletableFuture.completedFuture(
                                    assemble(requested, chosen, joined.pages(), joined.truncated())));
        });
    }

    /** Reads one listing page's cards; a failed read is left for the caller to classify. */
    private CompletableFuture<List<String>> readSlugs(String url) {
        return ClientIoExecutor.supplyAsync(() -> pages.read(url));
    }

    /** The site pages of one logical page, plus how many of the extra reads failed. */
    private Joined joinPages(List<String> opening, List<CompletableFuture<List<String>>> rest,
                             int requested) {
        List<List<String>> joined = new ArrayList<>(rest.size() + 1);
        joined.add(opening);
        int failures = 0;
        boolean truncated = false;
        for (CompletableFuture<List<String>> later : rest) {
            if (later.isCompletedExceptionally() || later.isCancelled()) {
                failures++;
                truncated = true;
                continue;
            }
            if (truncated) {
                // A gap makes the later pages non-contiguous with what was read, so drop them.
                continue;
            }
            joined.add(later.getNow(List.of()));
        }
        if (failures > 0) {
            QuickSkinInfo.LOGGER.warn(
                    "{} of {} further mcskins.top listing pages failed on page {}; showing what arrived",
                    failures, rest.size(), requested);
        }
        return new Joined(joined, truncated, failures == rest.size() && !rest.isEmpty());
    }

    /** The cards read for one logical page and whether the listing is known to continue. */
    private record Joined(List<List<String>> pages, boolean truncated, boolean allFailed) {
    }

    /**
     * Joins the site pages of one logical page. Each card carries only a slug, so the arm model is
     * decided when its PNG arrives; the entry starts as classic, like a fresh import.
     */
    private ElyByCatalogService.CatalogPage assemble(int logicalPage, Window window,
                                                     List<List<String>> pages, boolean truncated) {
        List<ElyByCatalogService.CatalogItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> opening = pages.isEmpty() ? List.of() : pages.get(0);
        if (logicalPage == 1 && !opening.isEmpty()) {
            openingPages.put(window, opening);
        }
        List<String> knownOpening = openingPages.get(window);
        List<String> previous = null;
        boolean reachedEnd = pages.isEmpty();
        for (int index = 0; index < pages.size(); index++) {
            List<String> slugs = pages.get(index);
            if (slugs.isEmpty()) {
                reachedEnd = true;
                break;
            }
            // Past its last page the site answers with page one again, so a repeat means the end.
            if (previous != null && slugs.equals(previous)) {
                reachedEnd = true;
                break;
            }
            if (index > 0 && knownOpening != null && slugs.equals(knownOpening)) {
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
                reachedEnd && !truncated ? logicalPage : logicalPage + 1, items.size());
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
            Path cached = CatalogCache.fileFor(reference);
            byte[] ready = CatalogCache.read(cached);
            if (ready != null) {
                return ready;
            }
            if (!SLUG.matcher(slug).matches()) {
                throw new ElyByCatalogService.CatalogException("Untrusted mcskins.top texture reference", null);
            }
            byte[] png = read(SKIN_BASE + slug + ".png", MAX_SKIN_BYTES);
            if (!CatalogCache.isPng(png)) {
                throw new ElyByCatalogService.CatalogException("mcskins.top answered something that is not a PNG", null);
            }
            CatalogCache.write(cached, png);
            return png;
        });
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
