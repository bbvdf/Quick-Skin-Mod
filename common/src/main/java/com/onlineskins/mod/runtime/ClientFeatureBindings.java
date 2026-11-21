package com.onlineskins.mod.runtime;

import com.onlineskins.mod.client.api.CpmAssetAccess;
import com.onlineskins.mod.client.compat.CPMCompatIntegration;
import com.onlineskins.mod.client.compat.CustomNPCsIntegration;
import com.onlineskins.mod.client.gui.screen.SettingsScreen;
import com.onlineskins.mod.client.services.LocalAssetManager;
import com.onlineskins.mod.client.services.PlayerAppearanceService;
import com.onlineskins.mod.client.storage.NetworkTextureCache;
import com.onlineskins.mod.common.data.AssetMetadata;
import com.onlineskins.mod.networking.ClientNetworkHandler;
import com.onlineskins.mod.platform.MinecraftTextures;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/** Process-owned feature wiring, installed before catalog scans, events, or packet receivers. */
@Environment(EnvType.CLIENT)
public final class ClientFeatureBindings {
    private static boolean installed;

    private ClientFeatureBindings() {
    }

    public static synchronized void install() {
        if (installed) return;
        com.onlineskins.mod.networking.TextureRequestCoordinator.configureConnection(
                () -> Minecraft.getInstance().getConnection());
        com.onlineskins.mod.client.api.ClientNetworkApi.bind(
                new com.onlineskins.mod.networking.NetworkClientActions());
        CPMCompatIntegration.configureAssets(new CpmAssetAccess() {
            @Override
            public AssetMetadata metadata(String contentId) {
                return LocalAssetManager.getInstance().getMetadata(contentId);
            }

            @Override
            public Path localSource(String contentId) {
                return LocalAssetManager.getInstance().getSourcePath(contentId);
            }

            @Override
            public Path networkSkinFile(String contentId) {
                return NetworkTextureCache.getInstance().getOrCreateTempFile(contentId, "skin");
            }
        });
        PlayerAppearanceService appearance = PlayerAppearanceService.getInstance();
        appearance.configureSkinListener((playerId, texture) ->
                CustomNPCsIntegration.onSkinApplied(playerId, MinecraftTextures.location(texture)));
        ClientNetworkHandler.configureAppearance(appearance, ClientFeatureBindings::settingsScreenOpen);
        // A skin uploaded to Mojang becomes the own skin as Mojang keeps it
        com.onlineskins.mod.client.util.MojangSkinUploader.setUploadListener(
                com.onlineskins.mod.client.importing.PlayerOwnSkinBootstrap::adoptUploadedSkin);
        // Name tags show the nicknames the connected server announced
        NicknameDisplay.bindClientNicknames(
                com.onlineskins.mod.common.data.PlayerNicknameRepository.getInstance()::nickname);
        installed = true;
    }

    private static boolean settingsScreenOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return false;
        //? if <26.2 {
        return minecraft.screen instanceof SettingsScreen;
        //?} else {
        return minecraft.gui.screen() instanceof SettingsScreen;
        //?}
    }
}
