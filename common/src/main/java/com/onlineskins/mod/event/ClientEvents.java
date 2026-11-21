package com.onlineskins.mod.event;

import com.onlineskins.mod.platform.QuickSkinInfo;
import com.onlineskins.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.onlineskins.mod.client.services.AnimatedTextureManager;
import com.onlineskins.mod.client.importing.PlayerOwnSkinBootstrap;
import com.onlineskins.mod.common.event.InternalEventBus;
import com.onlineskins.mod.common.event.PlayerAppearanceUpdateEvent;
import com.onlineskins.mod.common.event.ServerConfigSyncEvent;
import com.onlineskins.mod.common.event.SkinTexturesReloadedEvent;
import com.onlineskins.mod.runtime.ClientRuntime;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static final java.util.List<InternalEventBus.Subscription> INTERNAL_SUBSCRIPTIONS =
            new java.util.ArrayList<>();
    private static boolean initialized;

    public static String getSharedAnimation() {
        return com.onlineskins.mod.client.services.PreviewAnimationState.get();
    }

    public static void setSharedAnimation(String animation) {
        com.onlineskins.mod.client.services.PreviewAnimationState.set(animation);
    }

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        init(ClientRuntime.getInstance());
    }

    /** Registers platform callbacks and binds them to the client-owned session runtime. */
    public static synchronized void init(ClientRuntime clientRuntime) {
        if (initialized) {
            return;
        }
        if (clientRuntime == null) {
            throw new IllegalArgumentException("clientRuntime cannot be null");
        }
        PlayerOwnSkinBootstrap.initialize();
        initialized = true;

        registerInternalListeners();
        com.onlineskins.mod.client.services.CpmLookArbiter.bindLiveSession(
                () -> !com.onlineskins.mod.client.compat.ReplayModHelper.isInReplay());

        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // The session user is not readable from every platform's client entry point, so retry
            // the own-skin bootstrap from the first tick that runs. It disarms itself once started.
            PlayerOwnSkinBootstrap.ensurePlayerOwnSkinExists();

            com.onlineskins.mod.client.compat.CPMCompatIntegration
                    .prepareForBackgroundModelLoading();
            // This also ensures the singleton instance is created.
            com.onlineskins.mod.client.storage.NetworkTextureCache.getInstance()
                    .tickWorkingSet();
            AnimatedTextureManager.getInstance().tick();
            com.onlineskins.mod.networking.ClientNetworkHandler.tick();
            com.onlineskins.mod.networking.NetworkSyncService.getInstance().tick();
            // The latest look choice between Quick Skin and CPM decides the local look.
            com.onlineskins.mod.client.services.CpmLookArbiter.tick();
            com.onlineskins.mod.client.compat.ReplayModHelper.tick();
        });

        // Download player's own skin on startup (async, won't block). Platforms whose client entry
        // point runs before Minecraft exists retry this from the client tick registered above.
        PlayerOwnSkinBootstrap.ensurePlayerOwnSkinExists();

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> GameCallbacks.guarded(
                "set up its session for the world being joined", () -> {
            clientRuntime.beginSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : Minecraft.getInstance().getConnection());
            resetSessionUiState();

            //? if <1.21 {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.hasSingleplayerServer()) {
                com.onlineskins.mod.config.ServerConfig serverConfig = com.onlineskins.mod.config.ServerConfig.getInstance();
                com.onlineskins.mod.config.ClientConfig.getInstance().applyServerOverride(serverConfig);
            }
            //?}
            // Restore saved skin and model type from config
            restoreSavedAppearance(player);
        }));

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> GameCallbacks.guarded(
                "end its session for the world being left", () -> {
            boolean ended = clientRuntime.endSession(
                    player != null ? player.getUUID() : null,
                    player != null ? player.connection : null);
            if (!ended) {
                return;
            }
            resetSessionUiState();

            // Re-register appearance for Essential's title screen player model
            com.onlineskins.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
        }));

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> GameCallbacks.guarded(
                "put the saved look back after a respawn", () -> {

            // Re-apply appearance after respawn
            restoreSavedAppearance(newPlayer);
        }));

        com.onlineskins.mod.client.gui.integration.MenuIntegration.init(parent ->
                com.onlineskins.mod.client.gui.GuiCompat.openScreen(new PlayerSkinMenuScreen(parent)));

        com.onlineskins.mod.client.gui.overlay.HudPreviewIntegration.init();
        com.onlineskins.mod.client.gui.fan.FavouritesFanIntegration.init();

        //? if <1.21 {
        ClientScreenInputEvent.MOUSE_RELEASED_PRE.register((client, screen, mouseX, mouseY, button) -> {
            com.onlineskins.mod.client.gui.widget.PlayerWidget activeWidget =
                com.onlineskins.mod.client.gui.widget.PlayerWidget.getActiveInteractionWidget();
        //?} else {
        // Debug screen toggle (F3)
            //? if <1.21.9 {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyCode, scanCode, modifiers) -> {
            //?} else {
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyEvent) -> {
            //?}
            // This event is for screen key presses
            // Keybinds are handled separately in KeybindRegistry
            return EventResult.pass();
        });
        //?}

            //? if <1.21 {
            if (activeWidget != null && activeWidget.isInteracting()) {
                boolean handled = activeWidget.mouseReleased(mouseX, mouseY, button);
                if (handled) {
                    return EventResult.interruptTrue(); // Consume the event
                }
            }
            //?} else {
        // Raw input (for global keybinds outside of screens)
            //? if <1.21.9 {
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            //?} else {
        ClientRawInputEvent.KEY_PRESSED.register((client, action, keyEvent) -> {
            //?}
            // Keybinds will be registered separately
            // This is for raw key detection if needed
            //?}
            return EventResult.pass();
        });

        // CPM cache invalidation must happen after HUD extraction, including hidden HUDs.
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            if (getCurrentScreen(Minecraft.getInstance()) == null) {
                com.onlineskins.mod.client.compat.CPMCompatIntegration.onRenderedFrameBoundary();
            }
        });

    }

    /** Connects service/domain changes to rendering and presentation adapters. */
    private static void registerInternalListeners() {
        InternalEventBus eventBus = InternalEventBus.getInstance();
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                PlayerAppearanceUpdateEvent.class,
                ClientEvents::onPlayerAppearanceUpdated));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                SkinTexturesReloadedEvent.class,
                ClientEvents::onSkinTexturesReloaded));
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                ServerConfigSyncEvent.class,
                ClientEvents::onServerConfigSynced));
        //? if >=1.21 {
        INTERNAL_SUBSCRIPTIONS.add(eventBus.register(
                com.onlineskins.mod.common.event.NetworkAppearanceAppliedEvent.class,
                event -> com.onlineskins.mod.client.compat.ReplayModHelper.noteNetworkAppearance(event.playerId())));
        //?}
    }

    private static void onPlayerAppearanceUpdated(PlayerAppearanceUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null
                || !event.playerId().equals(minecraft.player.getUUID())) {
            return;
        }

        // Preview rendering observes domain changes without coupling the service to a concrete UI.
        com.onlineskins.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static void onSkinTexturesReloaded(SkinTexturesReloadedEvent event) {
        if (event.reason() != SkinTexturesReloadedEvent.Reason.TRANSPARENCY_POLICY) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Screen screen = getCurrentScreen(minecraft);
        if (screen instanceof PlayerSkinMenuScreen skinMenu) {
            skinMenu.refreshSkinList();
        }
    }

    private static void onServerConfigSynced(ServerConfigSyncEvent event) {
        // Disallowed transparency invalidates any alpha result captured before the server policy arrived.
        if (!event.isAllowTransparentSkins()) {
            com.onlineskins.mod.common.util.TextureAlphaDetector.clearCache();
        }
        com.onlineskins.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();
    }

    private static Screen getCurrentScreen(Minecraft minecraft) {
        //? if <26.2 {
        return minecraft.screen;
        //?} else {
        return minecraft.gui.screen();
        //?}
    }

    private static void resetSessionUiState() {
        com.onlineskins.mod.client.gui.integration.MenuIntegration.resetSessionState();
        com.onlineskins.mod.client.gui.overlay.HudPreviewIntegration.resetSessionState();
        com.onlineskins.mod.client.gui.fan.FavouritesFanIntegration.resetSessionState();
    }

    /** Unregisters internal service listeners during explicit client shutdown. */
    public static synchronized void close() {
        PlayerOwnSkinBootstrap.close();
        for (InternalEventBus.Subscription subscription : INTERNAL_SUBSCRIPTIONS) {
            try {
                subscription.close();
            } catch (RuntimeException error) {
                QuickSkinInfo.LOGGER.warn("Failed to unregister a QuickSkin internal event listener", error);
            }
        }
        INTERNAL_SUBSCRIPTIONS.clear();
        resetSessionUiState();
    }

    /** Restores only a live game subject; the Replay integration owns replay subjects. */
    private static void restoreSavedAppearance(LocalPlayer player) {
        if (com.onlineskins.mod.client.compat.ReplayModHelper.isInReplay()) {
            com.onlineskins.mod.client.compat.ReplayModHelper.startReplayPlayerWatcher();
            return;
        }
        if (player != null) {
            restoreSavedAppearanceToPlayer(player.getUUID());
        }
    }

    private static void restoreSavedAppearanceToPlayer(java.util.UUID targetPlayerId) {
        com.onlineskins.mod.client.services.SavedAppearanceRestorer.restore(targetPlayerId);
    }

    public static void autoSelectPlayerOwnSkin() {
        PlayerOwnSkinBootstrap.autoSelectPlayerOwnSkin();
    }
}
