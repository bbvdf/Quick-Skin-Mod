package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.services.ElyByCatalogService;
import com.quickskin.mod.client.services.ElyByCatalogService.CatalogItem;
import com.quickskin.mod.client.services.ElyByCatalogService.CatalogPage;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.platform.MinecraftTextureUploads;
import com.quickskin.mod.platform.QuickSkinInfo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Browses the public Ely.by skin catalog inside the game: 40 skins per page in a grid of faces,
 * paged back and forth. Clicking a skin downloads it, adds it to the local library and puts it on
 * the player, so the choice behaves exactly like one picked from the skin column.
 */
@Environment(EnvType.CLIENT)
public class ElyByCatalogScreen extends Screen {

    private static final int COLUMNS = 8;
    private static final int ROWS = 5;
    private static final int TILE = 36;
    private static final int FACE = 30;
    private static final int GAP = 6;
    private static final int HEADER = 46;
    private static final int FOOTER = 42;
    private static final int GRID_WIDTH = COLUMNS * TILE + (COLUMNS - 1) * GAP;
    private static final int GRID_HEIGHT = ROWS * TILE + (ROWS - 1) * GAP;
    private static final int TEXT_COLOR = 0xFFF0F0F0;
    private static final int MUTED_COLOR = 0xFFA0A0A0;
    private static final int TILE_BACKGROUND = 0x60000000;
    private static final int TILE_HOVER = 0xC0FFFFFF;

    private final Screen parent;

    private int page = 1;
    private int lastPage = 1;
    private int totalItems = 0;
    private boolean loading;
    private boolean failed;
    private boolean applying;
    private List<CatalogItem> items = List.of();

    /** Registered face textures by catalog id, and the ids whose download is in flight. */
    private final Map<Long, Identifier> faces = new HashMap<>();
    private final Map<Long, Integer> faceSizes = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();

    private Button previousButton;
    private Button nextButton;

    public ElyByCatalogScreen(Screen parent) {
//? if <1.21 {
        super(Component.translatable("quickskin.elyby.title"));
//?} else {
        super(Component.literal("Ely.by"));
//?}
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonY = this.height - FOOTER + 12;
        int buttonWidth = 80;
        int center = this.width / 2;

        previousButton = Button.builder(Component.translatable("quickskin.elyby.previous"),
                        button -> changePage(-1))
                .bounds(center - buttonWidth - 8, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(previousButton);

        nextButton = Button.builder(Component.translatable("quickskin.elyby.next"),
                        button -> changePage(1))
                .bounds(center + 8, buttonY, buttonWidth, 20)
                .build();
        addRenderableWidget(nextButton);

        addRenderableWidget(Button.builder(Component.translatable("quickskin.button.close"),
                        button -> onClose())
                .bounds(this.width - 88, buttonY, 80, 20)
                .build());

        updatePagingButtons();
        if (items.isEmpty() && !loading) {
            loadPage(1);
        }
    }

    private void changePage(int delta) {
        if (loading || page + delta < 1) {
            return;
        }
        int target = page + delta;
        if (lastPage > 0 && target > lastPage) {
            return;
        }
        loadPage(target);
    }

    private void loadPage(int target) {
        loading = true;
        failed = false;
        page = target;
        items = List.of();
        releaseFaces();
        requested.clear();
        updatePagingButtons();
        ElyByCatalogService.getInstance().fetchPage(target)
                .thenAccept(page -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> acceptPage(page));
                    }
                })
                .exceptionally(throwable -> {
                    QuickSkinInfo.LOGGER.warn("Unable to load the Ely.by catalog page {}", target, throwable);
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

    private void acceptPage(CatalogPage loaded) {
        loading = false;
        failed = false;
        items = loaded.items;
        page = loaded.page;
        lastPage = loaded.lastPage;
        totalItems = loaded.totalItems;
        updatePagingButtons();
        for (CatalogItem item : items) {
            requestFace(item);
        }
    }

    private void updatePagingButtons() {
        if (previousButton != null) previousButton.active = !loading && page > 1;
        if (nextButton != null) {
            nextButton.active = !loading && (lastPage <= 0 || page < lastPage);
        }
    }

    /** Downloads the catalog entry's texture once and registers it as a face to draw. */
    private void requestFace(CatalogItem item) {
        if (faces.containsKey(item.id) || !requested.add(item.id)) {
            return;
        }
        ElyByCatalogService.getInstance().download(item.skinUrl)
                .thenAccept(bytes -> {
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> registerFace(item.id, bytes));
                    }
                })
                .exceptionally(throwable -> {
                    // A missing face only leaves its tile empty; the rest of the page still shows.
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
            int size = image.getWidth();
            DynamicTexture texture = MinecraftTextureUploads.create(() -> "quickskin_elyby_" + id, image);
            Identifier location = Identifier.fromNamespaceAndPath(QuickSkinInfo.MOD_ID, "elyby/" + id);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            faces.put(id, location);
            faceSizes.put(id, size);
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
        graphics.drawString(this.font, "Ely.by", 12, 14, TEXT_COLOR, false);
//?} else {
        graphics.text(this.font, "Ely.by", 12, 14, TEXT_COLOR, false);
//?}
        String counter = page + "/" + Math.max(1, lastPage) + "  ·  " + totalItems;
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
        if (items.isEmpty()) {
            drawCenter(graphics, Component.translatable("quickskin.elyby.empty").getString(),
                    this.height / 2, MUTED_COLOR);
            return;
        }

        int originX = (this.width - GRID_WIDTH) / 2;
        int originY = HEADER;
        int hovered = tileAt(mouseX, mouseY);
        for (int index = 0; index < items.size() && index < COLUMNS * ROWS; index++) {
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            int left = originX + column * (TILE + GAP);
            int top = originY + row * (TILE + GAP);

            graphics.fill(left, top, left + TILE, top + TILE, TILE_BACKGROUND);
            Identifier face = faces.get(items.get(index).id);
            if (face != null) {
                int size = faceSizes.getOrDefault(items.get(index).id, 64);
                int inset = (TILE - FACE) / 2;
                GuiCompat.blit(graphics, face, left + inset, top + inset, FACE, FACE,
                        8.0F, 8.0F, 8, 8, size, size);
            } else {
                graphics.fill(left + (TILE - FACE) / 2, top + (TILE - FACE) / 2,
                        left + (TILE + FACE) / 2, top + (TILE + FACE) / 2, 0x40FFFFFF);
            }
            if (index == hovered) {
                graphics.fill(left - 1, top - 1, left + TILE + 1, top + 1, TILE_HOVER);
                graphics.fill(left - 1, top + TILE, left + TILE + 1, top + TILE + 1, TILE_HOVER);
                graphics.fill(left - 1, top, left + 1, top + TILE, TILE_HOVER);
                graphics.fill(left + TILE, top, left + TILE + 1, top + TILE, TILE_HOVER);
            }
        }

        if (applying) {
            drawCenter(graphics, Component.translatable("quickskin.elyby.applying").getString(),
                    originY + GRID_HEIGHT + 12, 0xFFFFFF80);
        }
    }

    /** The catalog index under the cursor, or -1. */
    private int tileAt(double mouseX, double mouseY) {
        int originX = (this.width - GRID_WIDTH) / 2;
        int originY = HEADER;
        double localX = mouseX - originX;
        double localY = mouseY - originY;
        if (localX < 0 || localY < 0) return -1;
        int column = (int) (localX / (TILE + GAP));
        int row = (int) (localY / (TILE + GAP));
        if (column >= COLUMNS || row >= ROWS) return -1;
        if (localX - column * (TILE + GAP) > TILE || localY - row * (TILE + GAP) > TILE) return -1;
        int index = row * COLUMNS + column;
        return index < items.size() ? index : -1;
    }

    //? if <26.1 {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickCatalog(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }
    //?} else {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        return clickCatalog(event.x(), event.y(), GuiCompat.mouseButton(event)) || super.mouseClicked(event, focused);
    }
    //?}

    private boolean clickCatalog(double mouseX, double mouseY, int button) {
        if (button != 0 || loading || applying) {
            return false;
        }
        int index = tileAt(mouseX, mouseY);
        if (index < 0) {
            return false;
        }
        apply(items.get(index));
        return true;
    }

    /** Download the chosen skin, save it into the local library and put it on the player. */
    private void apply(CatalogItem item) {
        applying = true;
        ElyByCatalogService.getInstance().download(item.skinUrl)
                .thenApply(bytes -> {
                    try {
                        BufferedImage image = SafeImageReader.readSkin(bytes);
                        return SkinImporter.saveSkinImage(image, "elyby-" + item.id);
                    } catch (Exception malformed) {
                        throw new IllegalStateException(malformed);
                    }
                })
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
