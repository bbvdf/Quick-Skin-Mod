package com.onlineskins.mod.client.gui.panel;

import com.onlineskins.mod.client.gui.widget.ChoiceBubble;
import com.onlineskins.mod.client.gui.widget.ConfirmButton;
import com.onlineskins.mod.client.gui.widget.IconButton;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The buttons that float around the player model, and Confirm below it.
 *
 * <p>Body width and Upload to Mojang sit at shoulder height, Skin websites and Cape at the waist,
 * left and right of the model, as square icon buttons in Confirm's style at the inner end of their
 * rows; the left ones open their tooltips to the left, away from the model. Skin websites opens a bubble to its left, pointing at it, with a Normal and an HD button
 * stacked inside, and stays highlighted while the bubble is open. This panel draws nothing; it only
 * places the buttons.
 */
public class ActionButtonsPanel extends AbstractWidget {

    /** Side of the square icon buttons, every floating button but the website choices. */
    public static final int ICON_BUTTON_SIZE = 24;
    private static final int SPACING = 2;
    /**
     * The website choices draw their labels at this many GUI pixels per font pixel and take their
     * height from it. One is crisp at every GUI scale, so the bubble, and the room it leaves the
     * model beside it, keep the same proportions in every window; a fraction would round to a
     * different size at each scale (1.15 gives one at scale 3 but 1.25 at scale 4).
     */
    private static final float CHOICE_LABEL_SCALE = 1.0f;
    /** Room between the bubble's edge and its choices. */
    private static final int BUBBLE_PADDING = 4;
    /** Gap between the bubble's pointer and the button it points at. */
    private static final int POINTER_GAP = 1;
    /** The middle row of an icon button's face, above its lip, where the bubble points. */
    private static final int ICON_FACE_MIDDLE = 11;
    /**
     * How far the middle button of each side stands out from the model, so the columns curve
     * around it in a shallow arc instead of standing as straight rows; less where the column has
     * no room for it.
     */
    public static final int ARC_BULGE = 10;
    /** The website bubble stays at least this far from the screen's left edge. */
    private static final int BUBBLE_SCREEN_MARGIN = 2;

    private IconButton bodyWidthButton;
    private IconButton websitesButton;
    private ChoiceBubble websitesBubble;
    private Button normalWebsiteButton;
    private Button hdWebsiteButton;
    private Button mcSkinsWebsiteButton;
    private Button mineSkinWebsiteButton;
    private IconButton capeButton;
    private IconButton uploadButton;
    private IconButton comingSoonButton;
    private Button confirmButton;
    private boolean websitesOpen;

    /**
     * Callbacks for button actions
     */
    public record ActionCallbacks(
        Runnable onBodyWidth,
        Runnable onCraftyLibrary,
        Runnable onElyByLibrary,
        Runnable onMcSkinsLibrary,
        Runnable onMineSkinLibrary,
        Runnable onCape,
        Runnable onConfirm,
        Runnable onUpload
    ) {}

    /**
     * Where the buttons go: the left and right column x, their three rows and the floating
     * buttons' width, then Confirm's top-left corner and size. Confirm is larger than the other
     * buttons: it is the menu's main action.
     */
    public record Layout(int leftX, int rightX, int shoulderY, int waistY, int bottomY, int buttonWidth,
                         int confirmX, int confirmY, int confirmWidth, int confirmHeight) {}

    public ActionButtonsPanel(Layout layout) {
        super(layout.leftX(), layout.shoulderY(), layout.rightX() + layout.buttonWidth() - layout.leftX(),
                layout.confirmY() + layout.confirmHeight() - layout.shoulderY(), Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.onlineskins.mod.client.gui.screen.PlayerSkinMenuScreen screen, Layout layout,
                     ActionCallbacks callbacks) {
        int iconX = layout.leftX() + layout.buttonWidth() - ICON_BUTTON_SIZE;
        // The middle row stands out as far on both sides. The website bubble opens beside the
        // left one and may then cover the skin column's edge; it only has to stay on screen
        int leftBulge = Math.max(0, Math.min(ARC_BULGE,
                iconX - minimumButtonWidth(screen.getFont()) + ICON_BUTTON_SIZE - BUBBLE_SCREEN_MARGIN));
        int rightBulge = rightBulge(layout);
        int websitesX = iconX - leftBulge;
        bodyWidthButton = new IconButton(
            iconX, layout.shoulderY(), ICON_BUTTON_SIZE, "body_wide", Component.empty(),
            button -> callbacks.onBodyWidth().run()
        );
        screen.registerForegroundControl(bodyWidthButton);

        uploadButton = new IconButton(
            layout.rightX(), layout.shoulderY(), ICON_BUTTON_SIZE, "upload_skin",
            Component.translatable("onlineskins.button.upload_skin"),
            button -> callbacks.onUpload().run()
        );
        uploadButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.screen.upload.title")));
        screen.registerForegroundControl(uploadButton);

        websitesButton = new IconButton(
            websitesX, layout.waistY(), ICON_BUTTON_SIZE, "skin_websites",
            Component.translatable("onlineskins.button.skin_websites"),
            button -> setWebsitesOpen(!websitesOpen)
        );
        websitesButton.setTooltipOnLeft(Component.translatable("onlineskins.button.skin_websites"));
        screen.registerForegroundControl(websitesButton);

        // The bubble is registered first, so that its choices are drawn over it
        int choiceHeight = ConfirmButton.heightForLabel(CHOICE_LABEL_SCALE);
        int choiceWidth = choiceWidth(screen.getFont());
        int bubbleWidth = bubbleWidth(choiceWidth);
        int bubbleHeight = 4 * choiceHeight + 3 * SPACING + 2 * BUBBLE_PADDING;
        int pointerY = layout.waistY() + ICON_FACE_MIDDLE;
        int bubbleX = websitesX - POINTER_GAP - bubbleWidth;
        int bubbleY = pointerY - bubbleHeight / 2 + 1;
        websitesBubble = new ChoiceBubble(bubbleX, bubbleY, bubbleWidth, bubbleHeight, pointerY);
        screen.registerForegroundControl(websitesBubble);

        int choiceX = bubbleX + BUBBLE_PADDING;
        int choiceY = bubbleY + BUBBLE_PADDING;
        normalWebsiteButton = new ConfirmButton(
            choiceX, choiceY, choiceWidth, choiceHeight,
            Component.translatable("onlineskins.button.website_crafty"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onCraftyLibrary().run();
            }
        );
        normalWebsiteButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.button.website_crafty")));
        screen.registerForegroundControl(normalWebsiteButton);

        hdWebsiteButton = new ConfirmButton(
            choiceX, choiceY + choiceHeight + SPACING, choiceWidth, choiceHeight,
            Component.translatable("onlineskins.button.website_elyby"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onElyByLibrary().run();
            }
        );
        hdWebsiteButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.button.website_elyby")));
        screen.registerForegroundControl(hdWebsiteButton);

        mcSkinsWebsiteButton = new ConfirmButton(
            choiceX, choiceY + 2 * (choiceHeight + SPACING), choiceWidth, choiceHeight,
            Component.translatable("onlineskins.button.website_mcskins"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onMcSkinsLibrary().run();
            }
        );
        mcSkinsWebsiteButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.button.website_mcskins")));
        screen.registerForegroundControl(mcSkinsWebsiteButton);

        mineSkinWebsiteButton = new ConfirmButton(
            choiceX, choiceY + 3 * (choiceHeight + SPACING), choiceWidth, choiceHeight,
            Component.translatable("onlineskins.button.website_mineskin"),
            button -> {
                setWebsitesOpen(false);
                callbacks.onMineSkinLibrary().run();
            }
        );
        mineSkinWebsiteButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.button.website_mineskin")));
        screen.registerForegroundControl(mineSkinWebsiteButton);

        capeButton = new IconButton(
            layout.rightX() + rightBulge, layout.waistY(), ICON_BUTTON_SIZE, "cape",
            Component.translatable("onlineskins.button.cape"),
            button -> callbacks.onCape().run()
        );
        capeButton.setTooltip(Tooltip.create(Component.translatable("onlineskins.button.cape")));
        screen.registerForegroundControl(capeButton);

        // A feature still to come: greyed out, it only names itself
        comingSoonButton = new IconButton(
            iconX, layout.bottomY(), ICON_BUTTON_SIZE, "coming_soon",
            Component.translatable("onlineskins.button.coming_soon"),
            button -> { }
        );
        comingSoonButton.active = false;
        comingSoonButton.setTooltipOnLeft(Component.translatable("onlineskins.button.coming_soon"));
        screen.registerForegroundControl(comingSoonButton);

        confirmButton = new ConfirmButton(
            layout.confirmX(), layout.confirmY(), layout.confirmWidth(), layout.confirmHeight(),
            Component.translatable("onlineskins.button.confirm"),
            button -> callbacks.onConfirm().run()
        );
        screen.registerWidget(confirmButton);

        setWebsitesOpen(false);
    }

    private void setWebsitesOpen(boolean open) {
        websitesOpen = open;
        websitesButton.setSelected(open);
        websitesBubble.visible = open;
        normalWebsiteButton.visible = open;
        normalWebsiteButton.active = open;
        hdWebsiteButton.visible = open;
        hdWebsiteButton.active = open;
        mcSkinsWebsiteButton.visible = open;
        mcSkinsWebsiteButton.active = open;
        mineSkinWebsiteButton.visible = open;
        mineSkinWebsiteButton.active = open;
    }

    /**
     * The left column's tooltips, which open to the left so they never cover the model; Skin
     * websites shows none while its bubble is open beside it.
     */
    //? if <26.1 {
    public void renderLeftTooltips(GuiGraphics graphics, net.minecraft.client.gui.Font font, int mouseX, int mouseY) {
    //?} else {
    public void renderLeftTooltips(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int mouseX, int mouseY) {
    //?}
        if (bodyWidthButton == null) {
            return;
        }
        bodyWidthButton.renderTooltipOnLeft(graphics, font, mouseX, mouseY);
        if (!websitesOpen) {
            websitesButton.renderTooltipOnLeft(graphics, font, mouseX, mouseY);
        }
        comingSoonButton.renderTooltipOnLeft(graphics, font, mouseX, mouseY);
    }

    /** Whether the previewed skin can be uploaded to the player's Mojang account. */
    public void setUploadAvailable(boolean available) {
        if (uploadButton != null) {
            uploadButton.active = available;
        }
    }

    /**
     * Shows the previewed arm width on the Body width button, as its icon and in its tooltip; CPM
     * models have none to choose.
     */
    public void updateBodyWidth(boolean slim, Component modelName, boolean selectable) {
        if (bodyWidthButton == null) {
            return;
        }
        Component name = Component.translatable("onlineskins.menu.body_width", modelName);
        bodyWidthButton.setIcon(slim ? "body_slim" : "body_wide");
        bodyWidthButton.setMessage(name);
        bodyWidthButton.setTooltipOnLeft(name.copy().append("\n")
                .append(Component.translatable("onlineskins.tooltip.body_width")));
        bodyWidthButton.active = selectable;
    }

    /** Every button this panel places, for keeping the model from taking their clicks. */
    public java.util.List<Button> buttons() {
        return java.util.List.of(bodyWidthButton, uploadButton, websitesButton,
                normalWebsiteButton, hdWebsiteButton, mcSkinsWebsiteButton, mineSkinWebsiteButton, capeButton,
                comingSoonButton, confirmButton);
    }

    /** How far the right column's middle button stands out, within the room its column has. */
    public static int rightBulge(Layout layout) {
        return Math.max(0, Math.min(ARC_BULGE, layout.buttonWidth() - ICON_BUTTON_SIZE));
    }

    /** The narrowest column that holds an icon button with the website bubble beside it. */
    public static int minimumButtonWidth(net.minecraft.client.gui.Font font) {
        return bubbleWidth(choiceWidth(font)) + POINTER_GAP + ICON_BUTTON_SIZE;
    }

    /** The width of the website choices: the wider of the four labels at full size. */
    private static int choiceWidth(net.minecraft.client.gui.Font font) {
        int choiceHeight = ConfirmButton.heightForLabel(CHOICE_LABEL_SCALE);
        return Math.max(
                ConfirmButton.fittingWidth(font, Component.translatable("onlineskins.button.website_crafty"), choiceHeight),
                Math.max(
                        ConfirmButton.fittingWidth(font, Component.translatable("onlineskins.button.website_elyby"), choiceHeight),
                        Math.max(
                                ConfirmButton.fittingWidth(font, Component.translatable("onlineskins.button.website_mcskins"), choiceHeight),
                                ConfirmButton.fittingWidth(font, Component.translatable("onlineskins.button.website_mineskin"), choiceHeight))));
    }

    private static int bubbleWidth(int choiceWidth) {
        return choiceWidth + 2 * BUBBLE_PADDING + ChoiceBubble.POINTER_LENGTH;
    }

    public Button getConfirmButton() {
        return this.confirmButton;
    }

    @Override
    //? if <26.1 {
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
