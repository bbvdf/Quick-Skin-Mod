package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.services.CraftyCatalogService;
import com.quickskin.mod.client.services.ElyByCatalogService;
import com.quickskin.mod.client.services.ElyByCatalogService.CatalogItem;
import com.quickskin.mod.client.services.ElyByCatalogService.CatalogPage;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.platform.MinecraftTextureUploads;
import com.quickskin.mod.platform.QuickSkinInfo;
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

    private static final int ROWS = 3;
    private static final int MAX_COLUMNS = 6;
    private static final int MIN_TILE = 28;
    private static final int MAX_TILE = 96;
    private static final int GAP = 8;
    private static final int HEADER = 42;
    private static final int FOOTER = 50;
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int MUTED_COLOR = 0xFFA0A0A0;
    private static final int TILE_BACKGROUND = 0x50000000;
    private static final int TILE_HOVER = 0xD0FFFFFF;
    /** How much of the remaining angle each frame takes, the site's easing in a simpler form. */
    private static final float DAMPING = 0.25F;

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

    private Button previousButton;
    private Button nextButton;
    private Button sortButton;
    private Button sourceButton;
    /** Which public catalog this screen browses. */
    private CatalogSource source = CatalogSource.ELY;
    /** Index into the current source's orders: Ely.by has two, Crafty four. */
    private int sortIndex;

    /** The public skin catalogs this screen can browse. */
    private enum CatalogSource {
        ELY("Ely.by", 2),
        CRAFTY("Crafty", 4);

        private final String label;
        private final int orders;

        CatalogSource(String label, int orders) {
            this.label = label;
            this.orders = orders;
        }

        CatalogSource next() {
            return this == ELY ? CRAFTY : ELY;
        }
    }

    public ElyByCatalogScreen(Screen parent) {
        super(Component.translatable("quickskin.elyby.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layoutGrid();
        QuickSkinInfo.LOGGER.info("Ely.by catalog opened at {}x{}, {} columns of {}px, {} per screen",
                this.width, this.height, columns, tile, perScreen());

        int buttonY = this.height - FOOTER + 14;
        int buttonWidth = 58;
        int step = buttonWidth + 4;
        int center = this.width / 2;
        int rowX = center - (buttonWidth * 5 + 4 * 4) / 2;

        sourceButton = Button.builder(Component.literal(source.label),
                        button -> switchSource())
                .bounds(rowX, buttonY, buttonWidth, 20)
                .build();
        sourceButton.setTooltip(Tooltip.create(Component.translatable("quickskin.elyby.source_tip")));
        addRenderableWidget(sourceButton);

        sortButton = Button.builder(sortLabel(), button -> {
                    sortIndex = (sortIndex + 1) % source.orders;
                    sortButton.setMessage(sortLabel());
                    loadPage(1, 0);
                })
                .bounds(rowX + step, buttonY, buttonWidth, 20)
                .build();
        sortButton.setTooltip(Tooltip.create(Component.translatable("quickskin.elyby.sort_tip")));
        addRenderableWidget(sortButton);

        previousButton = Button.builder(Component.translatable("quickskin.elyby.previous"),
                        button -> changeScreen(-1))
                .bounds(rowX + 2 * step, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(previousButton);

        nextButton = Button.builder(Component.translatable("quickskin.elyby.next"),
                        button -> changeScreen(1))
                .bounds(rowX + 3 * step, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(nextButton);

        addRenderableWidget(Button.builder(Component.translatable("quickskin.button.close"),
                        button -> onClose())
                .bounds(rowX + 4 * step, buttonY, buttonWidth, 20)
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
        gridY = HEADER;
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
            loadPage(catalogPage + 1, 0);
        } else if (target < 0 && catalogPage > 1) {
            // -1 asks for the last screen of the page that loads
            loadPage(catalogPage - 1, -1);
        }
    }

    private void loadPage(int page, int targetScreen) {
        loading = true;
        failed = false;
        catalogPage = page;
        pageItems = List.of();
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
    }

    private void updatePagingButtons() {
        if (previousButton != null) {
            previousButton.active = !loading && (screenIndex > 0 || catalogPage > 1);
        }
        if (nextButton != null) {
            nextButton.active = !loading && (screenIndex < screenCount() - 1 || catalogPage < lastCatalogPage);
        }
    }

    private Component sortLabel() {
        if (source == CatalogSource.ELY) {
            return Component.translatable(sortIndex == 1 ? "quickskin.elyby.sort_new" : "quickskin.elyby.sort_hot");
        }
        switch (sortIndex) {
            case 1:
                return Component.translatable("quickskin.elyby.sort_new");
            case 2:
                return Component.translatable("quickskin.elyby.sort_viewed");
            case 3:
                return Component.translatable("quickskin.elyby.sort_trending");
            default:
                return Component.translatable("quickskin.elyby.sort_hot");
        }
    }

    /** Trade one public catalog for the other, dropping whatever the old one had loaded. */
    private void switchSource() {
        source = source.next();
        sortIndex = 0;
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
        loadPage(1, 0);
    }

    /** The texture bytes for an entry, from whichever catalog the entry came from. */
    private CompletableFuture<byte[]> downloadTexture(CatalogItem item) {
        return item.skinUrl.startsWith(CraftyCatalogService.REF_PREFIX)
                ? CraftyCatalogService.getInstance().download(item.skinUrl)
                : ElyByCatalogService.getInstance().download(item.skinUrl);
    }

    /** The local asset name prefix for an entry, so an import says where it came from. */
    private static String importPrefix(CatalogItem item) {
        return item.skinUrl.startsWith(CraftyCatalogService.REF_PREFIX) ? "crafty-" : "elyby-";
    }

    /** What one page of the current source's list means. */
    private CompletableFuture<CatalogPage> fetchCatalogPage(int page) {
        if (source == CatalogSource.ELY) {
            return ElyByCatalogService.getInstance().fetchPage(page, sortIndex == 1);
        }
        CraftyCatalogService.Sort order;
        switch (sortIndex) {
            case 1:
                order = CraftyCatalogService.Sort.NEWEST;
                break;
            case 2:
                order = CraftyCatalogService.Sort.TOP_VIEWED;
                break;
            case 3:
                order = CraftyCatalogService.Sort.TRENDING;
                break;
            default:
                order = CraftyCatalogService.Sort.MOST_WORN;
                break;
        }
        return CraftyCatalogService.getInstance().fetchPage(page, order);
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
        downloadTexture(item)
                .thenAccept(bytes -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> registerFace(item.id, bytes));
                    }
                })
                .exceptionally(throwable -> {
                    // A missing figure only leaves its tile empty; the rest of the screen still shows.
                    QuickSkinInfo.LOGGER.debug("Unable to load Ely.by catalog skin {}", item.id, throwable);
                    return null;
                });
    }

    private void registerFace(long id, byte[] bytes) {
        if (faces.containsKey(id)) {
            return;
        }
        try {
            var image = NativeImage.read(new ByteArrayInputStream(bytes));
            DynamicTexture texture = MinecraftTextureUploads.create(() -> "quickskin_elyby_" + id, image);
            Identifier location = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "elyby/" + id);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            faces.put(id, location);
            faceSizes.put(id, new int[]{image.getWidth(), image.getHeight()});
            faceBytes.put(id, bytes);
        } catch (Exception malformed) {
            QuickSkinInfo.LOGGER.warn("Unable to register Ely.by catalog skin {}", id, malformed);
        }
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
            drawCenter(graphics, Component.translatable("quickskin.elyby.loading").getString(),
                    this.height / 2, MUTED_COLOR);
            return;
        }
        if (failed) {
            drawCenter(graphics, Component.translatable("quickskin.elyby.error").getString(),
                    this.height / 2, 0xFFFF8080);
            return;
        }
        List<CatalogItem> visible = visibleItems();
        if (visible.isEmpty()) {
            drawCenter(graphics, Component.translatable("quickskin.elyby.empty").getString(),
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
            drawCenter(graphics, Component.translatable("quickskin.elyby.applying").getString(),
                    gridY + gridHeight + 4, 0xFFFFFF80);
        }
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
        data.setModelType(item.slim ? "slim" : "classic");

        // The renderer scales a 32-unit model by scale/16, so the drawn height is 2*scale pixels
        float figureScale = Math.max(4.0F, (tile - 6.0F) / 2.0F);
        // The hovered tile turns with the pointer the way the site's cards do: across the tile it
        // looks left and right up to 45 degrees, up and down up to 30, and it eases back to face
        // the viewer when the pointer leaves. The easing is what the site's damping does.
        float targetYaw = 0.0F;
        float targetPitch = 0.0F;
        if (mouseX >= left && mouseX < left + tile && mouseY >= top && mouseY < top + tile) {
            float across = (mouseX - (left + tile / 2.0F)) / (tile / 2.0F);
            float down = (mouseY - (top + tile / 2.0F)) / (tile / 2.0F);
            targetYaw = Math.max(-1.0F, Math.min(1.0F, across)) * 45.0F;
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
        com.quickskin.mod.client.compat.CpmModelWorkflow.activateSkin(metadata.hash());
        if (this.minecraft != null && this.minecraft.player != null) {
            // "classic" and "slim" are the local preference values; "default" is Mojang's spelling
            PlayerAppearanceService.getInstance().applySkin(this.minecraft.player.getUUID(),
                    "local_skin:" + metadata.hash(), item.slim ? "slim" : "classic");
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
