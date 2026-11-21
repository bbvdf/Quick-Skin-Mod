package com.onlineskins.mod.client.gui.screen;

import com.onlineskins.mod.client.services.CraftyCatalogService;
import com.onlineskins.mod.client.services.ElyByCatalogService;
import com.onlineskins.mod.client.services.ElyByCatalogService.CatalogItem;
import com.onlineskins.mod.client.services.ElyByCatalogService.CatalogPage;
import com.onlineskins.mod.client.services.LocalAssetManager;
import com.onlineskins.mod.client.services.McSkinsCatalogService;
import com.onlineskins.mod.client.services.PlayerAppearanceService;
import com.onlineskins.mod.client.gui.GuiCompat;
import com.onlineskins.mod.client.gui.util.SkinImporter;
import com.onlineskins.mod.client.rendering.PlayerModelRenderer;
import com.onlineskins.mod.client.rendering.PreviewPlayerData;
import com.onlineskins.mod.common.data.AssetMetadata;
import com.onlineskins.mod.common.util.HashUtil;
import com.onlineskins.mod.common.util.HDTextureProcessor;
import com.onlineskins.mod.common.util.SafeImageReader;
import com.onlineskins.mod.common.util.SkinModelDetector;
import com.onlineskins.mod.platform.MinecraftTextureUploads;
import com.onlineskins.mod.platform.QuickSkinInfo;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Browses the public Ely.by skin catalog inside the game.
 * <p>
 * The catalog answers 40 entries per page, far more than a readable grid holds, so each catalog
 * page is shown as several screens of three rows. Paging walks those screens first and only
 * fetches another catalog page at either edge. Each tile draws the skin as a standing figure big
 * enough to tell one skin from another; clicking one downloads it, adds it to the local library
 * and puts it on the player.
 */
@Environment(EnvType.CLIENT)
public class ElyByCatalogScreen extends Screen {

    private static final int ROWS = 2;
    private static final int MAX_COLUMNS = 5;
    private static final int MIN_TILE = 28;
    private static final int MAX_TILE = 150;
    private static final int GAP = 8;
    private static final int HEADER = 42;
    private static final int FOOTER = 50;
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int MUTED_COLOR = 0xFFA0A0A0;
    private static final int TILE_BACKGROUND = 0x50000000;
    private static final int TILE_HOVER = 0xD0FFFFFF;
    /** How much of the remaining angle each frame takes, the site's easing in a simpler form. */
    private static final float DAMPING = 0.25F;
    /** Crafty's orders, in the sequence its ordering button cycles through. */
    private static final CraftyCatalogService.Sort[] CRAFTY_ORDERS = {
            CraftyCatalogService.Sort.MOST_WORN,
            CraftyCatalogService.Sort.NEWEST,
            CraftyCatalogService.Sort.TOP_VIEWED,
            CraftyCatalogService.Sort.TRENDING_GAINERS,
            CraftyCatalogService.Sort.TRENDING_GROWING,
            CraftyCatalogService.Sort.TRENDING_HOT
    };
    /** The windows the trending orders are measured over, as the site's tabs offer them. */
    private static final int[] WINDOW_DAYS = {1, 7, 14, 30};

    private final Screen parent;

    /** Grid geometry, recomputed from the window for every init(). */
    private int columns = 5;
    private int tile = 56;
    private int gridX;
    private int gridY = HEADER;
    private int gridWidth;
    private int gridHeight;

    private int catalogPage = 1;
    private int lastCatalogPage = 1;
    private int totalItems = 0;
    /** Which screen of the current catalog page is shown, 0-based. */
    private int screenIndex = 0;

    private boolean loading;
    private boolean failed;
    private boolean applying;
    private List<CatalogItem> pageItems = List.of();

    private final Map<Long, Identifier> faces = new HashMap<>();
    private final Map<Long, int[]> faceSizes = new HashMap<>();
    private final Map<Long, byte[]> faceBytes = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();
    /** One preview model per entry, reused every frame so a tile costs a draw and not an allocation. */
    private final Map<Long, PreviewPlayerData> previews = new HashMap<>();
    /** Each tile's current {yaw, pitch}, eased toward the pointer every frame. */
    private final Map<Long, float[]> angles = new HashMap<>();
    /**
     * The arm width each entry's own sheet shows, for catalogs that publish no model. Decided when
     * the texture arrives, before the tile draws its figure.
     */
    private final Map<Long, Boolean> slimOverrides = new HashMap<>();
    /** The page fetched ahead of the reader, and the catalog state it was fetched for. */
    private CatalogPage prefetchedPage;
    private String prefetchedKey;
    /** The page a prefetch is in flight for, so one page is never fetched twice. */
    private String prefetchingKey;
    /** Textures of the page read ahead, so its first screen draws at once when the reader turns. */
    private final Map<Long, byte[]> prefetchedFaces = new HashMap<>();

    private Button previousButton;
    private Button nextButton;
    private Button sortButton;
    private Button sourceButton;
    private Button windowButton;
    /** Which public catalog this screen browses. */
    private CatalogSource source = CatalogSource.CRAFTY;
    /** Index into the current source's orders: Crafty opens on "hot right now". */
    private int sortIndex = 5;
    /** Index into WINDOW_DAYS, for the trending orders only. */
    private int windowIndex = 1;
    /** Which menu is open: -1 none, 0 the ordering, 1 the window, 2 the catalog. */
    private int openMenu = -1;
    /** Screen rectangles of the open menu's rows, top to bottom, rebuilt every draw. */
    private final java.util.List<int[]> menuRects = new java.util.ArrayList<>();

    /** The public skin catalogs this screen can browse, Crafty first. */
    private enum CatalogSource {
        CRAFTY("Crafty", 6),
        ELY("Ely.by", 2),
        MCSKINS("MC Skins", 3);

        private final String label;
        private final int orders;

        CatalogSource(String label, int orders) {
            this.label = label;
            this.orders = orders;
        }
    }

    public ElyByCatalogScreen(Screen parent) {
        this(parent, true);
    }

    /** {@code craftyFirst} picks the catalog the screen opens on; Crafty is the default. */
    public ElyByCatalogScreen(Screen parent, boolean craftyFirst) {
        this(parent, craftyFirst ? 0 : 1);
    }

    /** Opens on the catalog at {@code sourceOrdinal} in {@link CatalogSource}: Crafty, Ely.by, MC Skins. */
    public ElyByCatalogScreen(Screen parent, int sourceOrdinal) {
        super(Component.translatable("onlineskins.elyby.title"));
        this.parent = parent;
        CatalogSource[] sources = CatalogSource.values();
        this.source = sources[Math.max(0, Math.min(sourceOrdinal, sources.length - 1))];
        this.sortIndex = this.source == CatalogSource.CRAFTY ? 5 : 0;
    }

    @Override
    protected void init() {
        layoutGrid();
        QuickSkinInfo.LOGGER.info("Ely.by catalog opened at {}x{}, {} columns of {}px, {} per screen",
                this.width, this.height, columns, tile, perScreen());

        int buttonWidth = 58;
        int step = buttonWidth + 4;
        int center = this.width / 2;
        int rowX = center - (buttonWidth * 6 + 5 * 4) / 2;
        int buttonY = this.height - FOOTER + 14;

        sourceButton = Button.builder(Component.literal(source.label),
                        button -> toggleMenu(2))
                .bounds(rowX, buttonY, buttonWidth, 20)
                .build();
        sourceButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.elyby.source_tip")));
        addRenderableWidget(sourceButton);

        sortButton = Button.builder(sortLabel(), button -> toggleMenu(0))
                .bounds(rowX + step, buttonY, buttonWidth, 20)
                .build();
        sortButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.elyby.sort_tip")));
        addRenderableWidget(sortButton);

        windowButton = Button.builder(windowLabel(), button -> toggleMenu(1))
                .bounds(rowX + 2 * step, buttonY, buttonWidth, 20)
                .build();
        windowButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.elyby.window_tip")));
        addRenderableWidget(windowButton);

        previousButton = Button.builder(Component.translatable("onlineskins.elyby.previous"),
                        button -> changeScreen(-1))
                .bounds(rowX + 3 * step, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(previousButton);

        nextButton = Button.builder(Component.translatable("onlineskins.elyby.next"),
                        button -> changeScreen(1))
                .bounds(rowX + 4 * step, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(nextButton);

        addRenderableWidget(Button.builder(Component.translatable("onlineskins.button.close"),
                        button -> onClose())
                .bounds(rowX + 5 * step, buttonY, buttonWidth, 20)
                .build());

        updatePagingButtons();
        if (pageItems.isEmpty() && !loading) {
            loadPage(1, 0);
        }
    }

    /**
     * Size the grid to the window: three rows, as many columns as fit, and a tile big enough to
     * show a whole figure without pushing the paging buttons over it.
     */
    private void layoutGrid() {
        int availableWidth = Math.max(200, this.width - 32);
        int availableHeight = Math.max(120, this.height - HEADER - FOOTER);
        columns = Math.max(3, Math.min(MAX_COLUMNS, (availableWidth + GAP) / (MIN_TILE + GAP)));
        int byWidth = (availableWidth - (columns - 1) * GAP) / columns;
        int byHeight = (availableHeight - (ROWS - 1) * GAP) / ROWS;
        tile = Math.max(MIN_TILE, Math.min(MAX_TILE, Math.min(byWidth, byHeight)));
        gridWidth = columns * tile + (columns - 1) * GAP;
        gridHeight = ROWS * tile + (ROWS - 1) * GAP;
        gridX = (this.width - gridWidth) / 2;
        gridY = HEADER + Math.max(0, (availableHeight - gridHeight) / 2);
    }

    private int perScreen() {
        return columns * ROWS;
    }

    private int screenCount() {
        return Math.max(1, (pageItems.size() + perScreen() - 1) / perScreen());
    }

    /** The entries of the current screen. */
    private List<CatalogItem> visibleItems() {
        int from = screenIndex * perScreen();
        if (from >= pageItems.size()) {
            return List.of();
        }
        return pageItems.subList(from, Math.min(pageItems.size(), from + perScreen()));
    }

    private void changeScreen(int delta) {
        if (loading) {
            return;
        }
        int target = screenIndex + delta;
        if (target >= 0 && target < screenCount()) {
            screenIndex = target;
            requestVisibleFaces();
            updatePagingButtons();
            return;
        }
        if (target >= screenCount() && catalogPage < lastCatalogPage) {
            showPage(catalogPage + 1, 0);
        } else if (target < 0 && catalogPage > 1) {
            // -1 asks for the last screen of the page that loads
            loadPage(catalogPage - 1, -1);
        }
    }

    /** Shows a page, using the one read ahead of the reader when it is the same page. */
    private void showPage(int page, int targetScreen) {
        if (prefetchedPage != null && catalogKey(page).equals(prefetchedKey)) {
            CatalogPage ready = prefetchedPage;
            prefetchedPage = null;
            prefetchedKey = null;
            releaseFaces();
            requested.clear();
            acceptPage(ready, targetScreen);
            return;
        }
        loadPage(page, targetScreen);
    }

    /** Identifies a page within the current catalog and order, so a stale prefetch is never used. */
    private String catalogKey(int page) {
        return source.name() + ":" + sortIndex + ":" + windowIndex + ":" + page;
    }

    /**
     * Reads the page after this one while the reader is still on this one, so turning past the last
     * screen of a page costs no network wait. Only the listing is read; textures still arrive per
     * tile once the page is shown.
     */
    private void prefetchNextPage() {
        int next = catalogPage + 1;
        if (next > lastCatalogPage) {
            return;
        }
        String key = catalogKey(next);
        if (key.equals(prefetchedKey) || key.equals(prefetchingKey)) {
            return;
        }
        prefetchingKey = key;
        fetchCatalogPage(next)
                .thenAccept(page -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            if (key.equals(prefetchingKey)) {
                                prefetchingKey = null;
                            }
                            // Keep it only while it is still the page we would show next.
                            if (key.equals(catalogKey(catalogPage + 1))) {
                                prefetchedPage = page;
                                prefetchedKey = key;
                                prefetchFaces(page, key);
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            if (key.equals(prefetchingKey)) {
                                prefetchingKey = null;
                            }
                        });
                    }
                    return null;
                });
    }

    /**
     * Downloads what the page read ahead shows on its first screen, so turning to it draws skins
     * instead of filling tile by tile. The rest of the page still arrives as it is scrolled to.
     */
    private void prefetchFaces(CatalogPage page, String key) {
        int count = Math.min(perScreen(), page.items.size());
        for (int index = 0; index < count; index++) {
            CatalogItem item = page.items.get(index);
            if (prefetchedFaces.containsKey(item.id)) {
                continue;
            }
            downloadTexture(item)
                    .thenAccept(bytes -> {
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                if (key.equals(prefetchedKey)) {
                                    prefetchedFaces.put(item.id, bytes);
                                }
                            });
                        }
                    })
                    .exceptionally(throwable -> {
                        // A tile that fails to arrive ahead of time is fetched again when shown.
                        return null;
                    });
        }
    }

    private void loadPage(int page, int targetScreen) {
        loading = true;
        failed = false;
        openMenu = -1;
        catalogPage = page;
        pageItems = List.of();
        // A page read ahead belongs to whichever page was next when it was fetched, not to this one.
        prefetchedPage = null;
        prefetchedKey = null;
        prefetchedFaces.clear();
        screenIndex = 0;
        releaseFaces();
        requested.clear();
        updatePagingButtons();
        fetchCatalogPage(page)
                .thenAccept(loaded -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> acceptPage(loaded, targetScreen));
                    }
                })
                .exceptionally(throwable -> {
                    QuickSkinInfo.LOGGER.warn("Unable to load the Ely.by catalog page {}", page, throwable);
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            loading = false;
                            failed = true;
                            updatePagingButtons();
                        });
                    }
                    return null;
                });
    }

    private void acceptPage(CatalogPage loaded, int targetScreen) {
        loading = false;
        failed = false;
        pageItems = loaded.items;
        catalogPage = loaded.page;
        lastCatalogPage = loaded.lastPage;
        totalItems = loaded.totalItems;
        screenIndex = targetScreen < 0 ? screenCount() - 1
                : Math.max(0, Math.min(targetScreen, screenCount() - 1));
        updatePagingButtons();
        requestVisibleFaces();
        prefetchNextPage();
    }

    private void updatePagingButtons() {
        if (previousButton != null) {
            previousButton.active = !loading && (screenIndex > 0 || catalogPage > 1);
        }
        if (nextButton != null) {
            nextButton.active = !loading && (screenIndex < screenCount() - 1 || catalogPage < lastCatalogPage);
        }
        if (windowButton != null) {
            // Only Crafty's trending orders are measured over a window; the other catalogs never
            // read it, so the button is not shown to them at all.
            windowButton.visible = source == CatalogSource.CRAFTY;
            windowButton.active = windowApplies();
        }
    }

    private Component sortLabel() {
        if (source == CatalogSource.ELY) {
            return Component.translatable(sortIndex == 1 ? "onlineskins.elyby.sort_new" : "onlineskins.elyby.sort_hot");
        }
        if (source == CatalogSource.MCSKINS) {
            return Component.translatable(sortIndex == 2 ? "onlineskins.elyby.sort_all"
                    : sortIndex == 1 ? "onlineskins.elyby.sort_week" : "onlineskins.elyby.sort_month");
        }
        switch (sortIndex) {
            case 1:
                return Component.translatable("onlineskins.elyby.sort_new");
            case 2:
                return Component.translatable("onlineskins.elyby.sort_viewed");
            case 3:
                return Component.translatable("onlineskins.elyby.sort_gainers");
            case 4:
                return Component.translatable("onlineskins.elyby.sort_growing");
            case 5:
                return Component.translatable("onlineskins.elyby.sort_hot_now");
            default:
                return Component.translatable("onlineskins.elyby.sort_hot");
        }
    }

    /** The window the trending orders are measured over. */
    private Component windowLabel() {
        switch (windowIndex) {
            case 0:
                return Component.translatable("onlineskins.elyby.window_today");
            case 2:
                return Component.translatable("onlineskins.elyby.window_two_weeks");
            case 3:
                return Component.translatable("onlineskins.elyby.window_month");
            default:
                return Component.translatable("onlineskins.elyby.window_week");
        }
    }

    /** Whether the chosen order on the chosen source is measured over a time window. */
    private boolean windowApplies() {
        return source == CatalogSource.CRAFTY && sortIndex >= 3;
    }

    private void toggleMenu(int menu) {
        openMenu = openMenu == menu ? -1 : menu;
    }

    /** Every order the current source offers, in the sequence CRAFTY_ORDERS and Ely.by use. */
    private List<Component> sortOptions() {
        if (source == CatalogSource.ELY) {
            return List.of(Component.translatable("onlineskins.elyby.sort_hot"),
                    Component.translatable("onlineskins.elyby.sort_new"));
        }
        if (source == CatalogSource.MCSKINS) {
            return List.of(Component.translatable("onlineskins.elyby.sort_month"),
                    Component.translatable("onlineskins.elyby.sort_week"),
                    Component.translatable("onlineskins.elyby.sort_all"));
        }
        return List.of(Component.translatable("onlineskins.elyby.sort_hot"),
                Component.translatable("onlineskins.elyby.sort_new"),
                Component.translatable("onlineskins.elyby.sort_viewed"),
                Component.translatable("onlineskins.elyby.sort_gainers"),
                Component.translatable("onlineskins.elyby.sort_growing"),
                Component.translatable("onlineskins.elyby.sort_hot_now"));
    }

    private List<Component> windowOptions() {
        return List.of(Component.translatable("onlineskins.elyby.window_today"),
                Component.translatable("onlineskins.elyby.window_week"),
                Component.translatable("onlineskins.elyby.window_two_weeks"),
                Component.translatable("onlineskins.elyby.window_month"));
    }

    /** Every public catalog this screen can browse, in the order the source button lists them. */
    private List<Component> sourceOptions() {
        List<Component> labels = new java.util.ArrayList<>(CatalogSource.values().length);
        for (CatalogSource option : CatalogSource.values()) {
            labels.add(Component.literal(option.label));
        }
        return labels;
    }

    /** Draws the open menu over the grid, and records each row for the click test. */
    private void drawMenu(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int mouseX, int mouseY) {
        menuRects.clear();
        if (openMenu < 0) {
            return;
        }
        List<Component> labels = openMenu == 0 ? sortOptions() : openMenu == 1 ? windowOptions() : sourceOptions();
        int itemHeight = 16;
        int width = 96;
        int anchorX = openMenu == 0 ? sortButton.getX() : openMenu == 1 ? windowButton.getX() : sourceButton.getX();
        int bottom = (openMenu == 0 ? sortButton.getY() : openMenu == 1 ? windowButton.getY() : sourceButton.getY()) - 2;
        int totalHeight = labels.size() * itemHeight + 4;
        int x = Math.max(2, Math.min(anchorX, this.width - width - 2));
        int y = bottom - totalHeight;

        graphics.fill(x, y, x + width, y + totalHeight, 0xF0141414);
        graphics.fill(x, y, x + width, y + 1, 0xFF808080);
        graphics.fill(x, y + totalHeight - 1, x + width, y + totalHeight, 0xFF808080);
        graphics.fill(x, y, x + 1, y + totalHeight, 0xFF808080);
        graphics.fill(x + width - 1, y, x + width, y + totalHeight, 0xFF808080);

        int current = openMenu == 0 ? sortIndex : openMenu == 1 ? windowIndex : source.ordinal();
        for (int index = 0; index < labels.size(); index++) {
            int rowY = y + 2 + index * itemHeight;
            int rowX = x + 2;
            int rowWidth = width - 4;
            boolean hover = mouseX >= rowX && mouseX < rowX + rowWidth
                    && mouseY >= rowY && mouseY < rowY + itemHeight;
            if (hover) {
                graphics.fill(rowX, rowY, rowX + rowWidth, rowY + itemHeight, 0x60FFFFFF);
            } else if (index == current) {
                graphics.fill(rowX, rowY, rowX + rowWidth, rowY + itemHeight, 0x30FFFFFF);
            }
//? if <26.1 {
            graphics.drawString(this.font, labels.get(index).getString(), rowX + 4, rowY + 4, TEXT_COLOR, false);
//?} else {
            graphics.text(this.font, labels.get(index).getString(), rowX + 4, rowY + 4, TEXT_COLOR, false);
//?}
            menuRects.add(new int[]{rowX, rowY, rowWidth, itemHeight});
        }
    }

    /** A click while a menu is open picks its row, or closes it and goes no further. */
    private boolean clickMenu(double mouseX, double mouseY) {
        for (int index = 0; index < menuRects.size(); index++) {
            int[] rect = menuRects.get(index);
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[2]
                    && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
                if (openMenu == 0) {
                    sortIndex = index;
                    sortButton.setMessage(sortLabel());
                } else if (openMenu == 1) {
                    windowIndex = index;
                    windowButton.setMessage(windowLabel());
                } else {
                    CatalogSource[] sources = CatalogSource.values();
                    applySource(sources[Math.max(0, Math.min(index, sources.length - 1))]);
                }
                openMenu = -1;
                updatePagingButtons();
                loadPage(1, 0);
                return true;
            }
        }
        openMenu = -1;
        return true;
    }

    /** Trade one public catalog for another, dropping whatever the old one had loaded. */
    private void applySource(CatalogSource chosen) {
        if (chosen == source) {
            return;
        }
        source = chosen;
        sortIndex = 0;
        openMenu = -1;
        sourceButton.setMessage(Component.literal(source.label));
        sortButton.setMessage(sortLabel());
        releaseFaces();
        faces.clear();
        faceSizes.clear();
        faceBytes.clear();
        previews.clear();
        angles.clear();
        requested.clear();
        pageItems = List.of();
        screenIndex = 0;
    }

    /** The texture bytes for an entry, from whichever catalog the entry came from. */
    private CompletableFuture<byte[]> downloadTexture(CatalogItem item) {
        if (item.skinUrl.startsWith(McSkinsCatalogService.REF_PREFIX)) {
            return McSkinsCatalogService.getInstance().download(item.skinUrl);
        }
        return item.skinUrl.startsWith(CraftyCatalogService.REF_PREFIX)
                ? CraftyCatalogService.getInstance().download(item.skinUrl)
                : ElyByCatalogService.getInstance().download(item.skinUrl);
    }

    /** The local asset name prefix for an entry, so an import says where it came from. */
    private static String importPrefix(CatalogItem item) {
        if (item.skinUrl.startsWith(McSkinsCatalogService.REF_PREFIX)) return "mcskins-";
        return item.skinUrl.startsWith(CraftyCatalogService.REF_PREFIX) ? "crafty-" : "elyby-";
    }

    /** What one page of the current source's list means. */
    private CompletableFuture<CatalogPage> fetchCatalogPage(int page) {
        if (source == CatalogSource.ELY) {
            return ElyByCatalogService.getInstance().fetchPage(page, sortIndex == 1);
        }
        if (source == CatalogSource.MCSKINS) {
            McSkinsCatalogService.Window[] windows = McSkinsCatalogService.Window.values();
            int index = Math.max(0, Math.min(sortIndex, windows.length - 1));
            return McSkinsCatalogService.getInstance().fetchPage(page, windows[index]);
        }
        int index = Math.max(0, Math.min(sortIndex, CRAFTY_ORDERS.length - 1));
        return CraftyCatalogService.getInstance()
                .fetchPage(page, CRAFTY_ORDERS[index], WINDOW_DAYS[windowIndex]);
    }

    /** Downloads the textures of the entries on this screen, and nothing else. */
    private void requestVisibleFaces() {
        for (CatalogItem item : visibleItems()) {
            requestFace(item);
        }
    }

    private void requestFace(CatalogItem item) {
        if (faces.containsKey(item.id) || !requested.add(item.id)) {
            return;
        }
        byte[] ready = prefetchedFaces.remove(item.id);
        if (ready != null) {
            registerFace(item, ready);
            return;
        }
        downloadTexture(item)
                .thenAccept(bytes -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> registerFace(item, bytes));
                    }
                })
                .exceptionally(throwable -> {
                    // A missing figure only leaves its tile empty; the rest of the screen still shows.
                    QuickSkinInfo.LOGGER.debug("Unable to load Ely.by catalog skin {}", item.id, throwable);
                    return null;
                });
    }

    private void registerFace(CatalogItem item, byte[] bytes) {
        long id = item.id;
        if (faces.containsKey(id)) {
            return;
        }
        try {
            // A legacy 64x32 sheet samples the wrong rows on a modern model. The import path
            // normalises one before saving, so the grid must normalise before rendering too.
            byte[] normalized = HDTextureProcessor.processHDSkin(new ByteArrayInputStream(bytes), true);
            if (normalized != null) {
                bytes = normalized;
            }
            var image = NativeImage.read(new ByteArrayInputStream(bytes));
            DynamicTexture texture = MinecraftTextureUploads.create(() -> "onlineskins_elyby_" + id, image);
            Identifier location = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "elyby/" + id);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            faces.put(id, location);
            faceSizes.put(id, new int[]{image.getWidth(), image.getHeight()});
            faceBytes.put(id, bytes);
            // mcskins.top cards say nothing about arm width, so the sheet itself decides it.
            if (item.skinUrl.startsWith(McSkinsCatalogService.REF_PREFIX)) {
                try {
                    slimOverrides.put(id,
                            "slim".equals(SkinModelDetector.detectSkinModel(SafeImageReader.readSkin(bytes))));
                } catch (Exception undetectable) {
                    // The entry keeps the classic arms it started with.
                }
            }
        } catch (Exception malformed) {
            QuickSkinInfo.LOGGER.warn("Unable to register Ely.by catalog skin {}", id, malformed);
        }
    }

    /** The arm width of an entry: the catalog's own when it publishes one, else what its sheet shows. */
    private boolean isSlim(CatalogItem item) {
        return slimOverrides.getOrDefault(item.id, item.slim);
    }

    private void releaseFaces() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (Identifier location : faces.values()) {
            try {
                textureManager.release(location);
            } catch (RuntimeException ignored) {
                // Already released with the screen.
            }
        }
        faces.clear();
        faceSizes.clear();
        faceBytes.clear();
        previews.clear();
        angles.clear();
        slimOverrides.clear();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawCatalog(graphics, mouseX, mouseY);
    }
    //?} else {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawCatalog(graphics, mouseX, mouseY);
    }
    //?}

    private void drawCatalog(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int mouseX, int mouseY) {
//? if <26.1 {
        graphics.drawString(this.font, source.label, 12, 14, TEXT_COLOR, false);
//?} else {
        graphics.text(this.font, source.label, 12, 14, TEXT_COLOR, false);
//?}
        String counter = (screenIndex + 1) + "/" + screenCount()
                + "  \u00b7  " + catalogPage + "/" + Math.max(1, lastCatalogPage)
                + "  \u00b7  " + totalItems;
        drawRight(graphics, counter, this.width - 12, 14, MUTED_COLOR);

        if (loading) {
            drawCenter(graphics, Component.translatable("onlineskins.elyby.loading").getString(),
                    this.height / 2, MUTED_COLOR);
            return;
        }
        if (failed) {
            drawCenter(graphics, Component.translatable("onlineskins.elyby.error").getString(),
                    this.height / 2, 0xFFFF8080);
            return;
        }
        List<CatalogItem> visible = visibleItems();
        if (visible.isEmpty()) {
            drawCenter(graphics, Component.translatable("onlineskins.elyby.empty").getString(),
                    this.height / 2, MUTED_COLOR);
            return;
        }

        int hovered = tileAt(mouseX, mouseY);
        for (int index = 0; index < visible.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            int left = gridX + column * (tile + GAP);
            int top = gridY + row * (tile + GAP);

            graphics.fill(left, top, left + tile, top + tile, TILE_BACKGROUND);
            drawFigure(graphics, visible.get(index), left, top, mouseX, mouseY);
            if (index == hovered) {
                graphics.fill(left - 1, top - 1, left + tile + 1, top + 1, TILE_HOVER);
                graphics.fill(left - 1, top + tile, left + tile + 1, top + tile + 1, TILE_HOVER);
                graphics.fill(left - 1, top, left + 1, top + tile, TILE_HOVER);
                graphics.fill(left + tile, top, left + tile + 1, top + tile, TILE_HOVER);
            }
        }

        if (applying) {
            drawCenter(graphics, Component.translatable("onlineskins.elyby.applying").getString(),
                    gridY + gridHeight + 4, 0xFFFFFF80);
        }
        drawMenu(graphics, mouseX, mouseY);
    }

    /**
     * Draws the skin as a real player model turned 45 degrees, feet on the tile's bottom edge, the
     * same way the skin menu's own preview draws it.
     */
    private void drawFigure(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            CatalogItem item, int left, int top, int mouseX, int mouseY) {
        Identifier texture = faces.get(item.id);
        if (texture == null) {
            int placeholder = tile / 3;
            graphics.fill(left + placeholder, top + placeholder, left + tile - placeholder,
                    top + tile - placeholder, 0x30FFFFFF);
            return;
        }
        PreviewPlayerData data = previews.computeIfAbsent(item.id, key -> new PreviewPlayerData());
        data.setSkinLocation(texture);
        data.setModelType(isSlim(item) ? "slim" : "classic");

        // The renderer scales a 32-unit model by scale/16, so the drawn height is 2*scale pixels
        float figureScale = Math.max(4.0F, (tile - 6.0F) / 2.0F);
        // The hovered tile turns with the pointer the way the site's cards do: across the tile it
        // swings a full half turn either way, so the pointer can walk it round to its back, and up
        // and down up to 30. It eases back to face the viewer when the pointer leaves; the easing
        // is what the site's damping does.
        float targetYaw = 0.0F;
        float targetPitch = 0.0F;
        if (mouseX >= left && mouseX < left + tile && mouseY >= top && mouseY < top + tile) {
            float across = (mouseX - (left + tile / 2.0F)) / (tile / 2.0F);
            float down = (mouseY - (top + tile / 2.0F)) / (tile / 2.0F);
            targetYaw = Math.max(-1.0F, Math.min(1.0F, across)) * 180.0F;
            targetPitch = Math.max(-1.0F, Math.min(1.0F, down)) * 30.0F;
        }
        float[] angle = angles.computeIfAbsent(item.id, key -> new float[2]);
        angle[0] += (targetYaw - angle[0]) * DAMPING;
        angle[1] += (targetPitch - angle[1]) * DAMPING;
        PlayerModelRenderer.renderPlayerModel(graphics, left + tile / 2, top + tile + 2,
                figureScale, 45.0F, data, mouseX, mouseY, true, angle[0], angle[1]);
    }

    private void blitPart(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            Identifier texture, int x, int y, int sourceWidth, int sourceHeight,
            int u, int v, float scale, int textureWidth, int textureHeight) {
        // The UVs above are the 64x64 layout; an HD sheet stores the same parts twice as far out
        float uv = textureWidth / 64.0F;
        GuiCompat.blit(graphics, texture, x, y,
                Math.max(1, Math.round(sourceWidth * scale)), Math.max(1, Math.round(sourceHeight * scale)),
                Math.round(u * uv), Math.round(v * uv),
                Math.round(sourceWidth * uv), Math.round(sourceHeight * uv),
                textureWidth, textureHeight);
    }

    /** The visible entry index under the cursor, or -1. */
    private int tileAt(double mouseX, double mouseY) {
        double localX = mouseX - gridX;
        double localY = mouseY - gridY;
        if (localX < 0 || localY < 0) return -1;
        int column = (int) (localX / (tile + GAP));
        int row = (int) (localY / (tile + GAP));
        if (column >= columns || row >= ROWS) return -1;
        if (localX - column * (tile + GAP) > tile || localY - row * (tile + GAP) > tile) return -1;
        return row * columns + column;
    }

    //? if <26.1 {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickCatalog(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }
    //?} else {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        return clickCatalog(GuiCompat.mouseX(event), GuiCompat.mouseY(event),
                GuiCompat.mouseButton(event)) || super.mouseClicked(event, focused);
    }
    //?}

    private boolean clickCatalog(double mouseX, double mouseY, int button) {
        // 26.3 numbers the mouse buttons 1-based; the shared constant is the only safe test
        if (button != InputConstants.MOUSE_BUTTON_LEFT || loading || applying) {
            return false;
        }
        if (openMenu >= 0) {
            return clickMenu(mouseX, mouseY);
        }
        int index = tileAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        List<CatalogItem> visible = visibleItems();
        if (index >= visible.size()) {
            return false;
        }
        apply(visible.get(index));
        return true;
    }

    /** Download the chosen skin, save it into the local library and put it on the player. */
    private void apply(CatalogItem item) {
        applying = true;
        QuickSkinInfo.LOGGER.info("Applying Ely.by catalog skin {}", item.id);
        // The figure is already downloaded for the grid, so a click never waits on the network
        byte[] cached = faceBytes.get(item.id);
        var bytesFuture = cached != null
                ? CompletableFuture.completedFuture(cached)
                : downloadTexture(item);
        bytesFuture.thenApply(bytes -> {
                    try {
                        BufferedImage image = SafeImageReader.readSkin(bytes);
                        return SkinImporter.saveSkinImage(image, importPrefix(item) + item.id);
                    } catch (Exception malformed) {
                        throw new IllegalStateException(malformed);
                    }
                })
                .orTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(path -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> finishApply(path, item));
                    }
                })
                .exceptionally(throwable -> {
                    QuickSkinInfo.LOGGER.warn("Unable to import the Ely.by skin {}", item.id, throwable);
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> applying = false);
                    }
                    return null;
                });
    }

    private void finishApply(Path path, CatalogItem item) {
        applying = false;
        if (path == null) {
            QuickSkinInfo.LOGGER.warn("Unable to save the Ely.by skin {}", item.id);
            return;
        }
        LocalAssetManager.getInstance().reload();
        String hash = HashUtil.computeFileContentId(path);
        AssetMetadata metadata = hash == null ? null : LocalAssetManager.getInstance().getMetadata(hash);
        if (metadata == null) {
            QuickSkinInfo.LOGGER.warn("The saved Ely.by skin of {} has no catalogue entry", item.id);
            return;
        }
        // Remember the choice even on the title screen, where there is no player to dress yet
        com.onlineskins.mod.client.compat.CpmModelWorkflow.activateSkin(metadata.hash());
        if (this.minecraft != null && this.minecraft.player != null) {
            // "classic" and "slim" are the local preference values; "default" is Mojang's spelling
            PlayerAppearanceService.getInstance().applySkin(this.minecraft.player.getUUID(),
                    "local_skin:" + metadata.hash(), isSlim(item) ? "slim" : "classic");
        }
        QuickSkinInfo.LOGGER.info("Ely.by skin {} imported as {}", item.id, metadata.hash());
        onClose();
    }

    private void drawCenter(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String text, int centerY, int color) {
        int x = (this.width - this.font.width(text)) / 2;
//? if <26.1 {
        graphics.drawString(this.font, text, x, centerY, color, false);
//?} else {
        graphics.text(this.font, text, x, centerY, color, false);
//?}
    }

    private void drawRight(
            //? if <26.1 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            String text, int right, int y, int color) {
        int x = right - this.font.width(text);
//? if <26.1 {
        graphics.drawString(this.font, text, x, y, color, false);
//?} else {
        graphics.text(this.font, text, x, y, color, false);
//?}
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
//? if <26.2 {
            this.minecraft.setScreen(parent);
//?} else {
            this.minecraft.gui.setScreen(parent);
//?}
        }
    }

    @Override
    public void removed() {
        releaseFaces();
        super.removed();
    }
}
