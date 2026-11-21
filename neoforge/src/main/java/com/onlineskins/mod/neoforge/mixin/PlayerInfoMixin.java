//? if <1.21.6 {
package com.onlineskins.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.onlineskins.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * NeoForge-specific mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoMixin implements com.onlineskins.mod.client.compat.QuickSkinSkinLookupAccess {

    @Shadow
    @Final
    private GameProfile profile;

    // Vanilla resolves the skin once and keeps it. SkinManagerMixin bakes an active Quick Skin
    // skin into that result, so a withdrawn skin needs a new lookup (refreshSkinLookup).
    @Shadow
    @Final
    @Mutable
    private Supplier<PlayerSkin> skinLookup;

    @Shadow
    private static Supplier<PlayerSkin> createSkinLookup(GameProfile profile) {
        throw new AssertionError();
    }

    @Unique
    private PlayerSkin onlineskins$cachedSkin = null;

    @Unique
    private ResourceLocation onlineskins$cachedOriginalTexture = null;

    @Unique
    private ResourceLocation onlineskins$cachedSkinLocation = null;
    @Unique
    private ResourceLocation onlineskins$cachedCapeLocation = null;
    @Unique
    private ResourceLocation onlineskins$cachedElytraLocation = null;
    @Unique
    private String onlineskins$cachedModelName = null;

    /** Replaces the vanilla skin lookup, which still holds a Quick Skin skin that was withdrawn. */
    @Override
    public void onlineskins$refreshSkinLookup() {
        this.skinLookup = createSkinLookup(this.profile);
        onlineskins$cachedSkin = null;
        onlineskins$cachedOriginalTexture = null;
    }

    @Inject(
            method = "getSkin",
            at = @At("TAIL"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void onlineskins$overrideSkinTail(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());
        boolean hasSeparateElytra = service.hasSeparateElytra(this.profile.getId());

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride && !hasSeparateElytra) {
            onlineskins$cachedSkin = null;
            onlineskins$cachedOriginalTexture = null;
            return;
        }

        PlayerSkin original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;
        ResourceLocation currentElytraLocation = hasSeparateElytra ? service.getSeparateElytraLocation(this.profile.getId()) : null;

        if (onlineskins$cachedSkin != null &&
            java.util.Objects.equals(onlineskins$cachedOriginalTexture, original.texture()) &&
            java.util.Objects.equals(onlineskins$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(onlineskins$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(onlineskins$cachedElytraLocation, currentElytraLocation) &&
            java.util.Objects.equals(onlineskins$cachedModelName, currentModelName)) {
            cir.setReturnValue(onlineskins$cachedSkin);
            return;
        }

        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();
        ResourceLocation elytraTexture = original.elytraTexture();

        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        }

        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                elytraTexture = currentCapeLocation;
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods or
                // leave an unrelated profile Elytra beside a pending custom cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        if (currentElytraLocation != null) {
            // The elytra wear another cape, or vanilla's elytra, instead of the cape
            elytraTexture = currentElytraLocation;
        }

        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            original.textureUrl(),
            capeTexture,
            elytraTexture,
            skinModel,
            original.secure()
        );

        onlineskins$cachedSkin = customSkin;
        onlineskins$cachedOriginalTexture = original.texture();
        onlineskins$cachedSkinLocation = currentSkinLocation;
        onlineskins$cachedCapeLocation = currentCapeLocation;
        onlineskins$cachedElytraLocation = currentElytraLocation;
        onlineskins$cachedModelName = currentModelName;

        cir.setReturnValue(customSkin);
    }
}
//?} else {
package com.onlineskins.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.onlineskins.mod.client.compat.CPMCompatIntegration;
import com.onlineskins.mod.client.services.LocalAssetManager;
import com.onlineskins.mod.client.services.PlayerAppearanceService;
import com.onlineskins.mod.common.data.AssetMetadata;
import com.onlineskins.mod.common.data.TextureQuality;
import com.onlineskins.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
//? if <1.21.9 {
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else if <1.21.11 {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
//? if <1.21.9 {
import org.spongepowered.asm.mixin.Mutable;
//?}
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * NeoForge-specific mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoMixin implements com.onlineskins.mod.client.compat.QuickSkinSkinLookupAccess {

    @Shadow
    @Final
    private GameProfile profile;

    // Vanilla resolves the skin once and keeps it. SkinManagerMixin bakes an active Quick Skin
    // skin into that result, so a withdrawn skin needs a new lookup (refreshSkinLookup).
    //? if <1.21.9 {
    @Shadow
    @Final
    @Mutable
    private Supplier<PlayerSkin> skinLookup;

    @Shadow
    private static Supplier<PlayerSkin> createSkinLookup(GameProfile profile) {
        throw new AssertionError();
    }
    //?} else {
    @Shadow
    private Supplier<PlayerSkin> skinLookup;
    //?}

    @Unique
    private PlayerSkin onlineskins$cachedSkin = null;

    @Unique
    //? if <1.21.11 {
    private ResourceLocation onlineskins$cachedOriginalTexture = null;
    //?} else {
    private Identifier onlineskins$cachedOriginalTexture = null;
    //?}

    @Unique
    //? if <1.21.11 {
    private ResourceLocation onlineskins$cachedSkinLocation = null;
    //?} else {
    private Identifier onlineskins$cachedSkinLocation = null;
    //?}
    @Unique
    //? if <1.21.11 {
    private ResourceLocation onlineskins$cachedCapeLocation = null;
    //?} else {
    private Identifier onlineskins$cachedCapeLocation = null;
    //?}
    @Unique
    //? if <1.21.11 {
    private ResourceLocation onlineskins$cachedElytraLocation = null;
    //?} else {
    private Identifier onlineskins$cachedElytraLocation = null;
    //?}
    @Unique
    private String onlineskins$cachedModelName = null;

    /** Replaces the vanilla skin lookup, which still holds a Quick Skin skin that was withdrawn. */
    @Override
    public void onlineskins$refreshSkinLookup() {
        //? if <1.21.9 {
        this.skinLookup = createSkinLookup(this.profile);
        //?} else {
        // getSkin() creates the lookup again when it is missing.
        this.skinLookup = null;
        //?}
        onlineskins$cachedSkin = null;
        onlineskins$cachedOriginalTexture = null;
    }

    @Inject(
            method = "getSkin",
            at = @At("TAIL"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1
    )
    private void onlineskins$overrideSkinTail(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

//? if <1.21.9 {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());
        boolean hasSeparateElytra = service.hasSeparateElytra(this.profile.getId());
//?} else {
        boolean hasCustomSkin = service.hasActiveSkin(this.profile.id());
        boolean hasCustomCape = service.hasActiveCape(this.profile.id());
        boolean hasModelOverride = service.hasModelOverride(this.profile.id());
        boolean hasSeparateElytra = service.hasSeparateElytra(this.profile.id());
//?}

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride && !hasSeparateElytra) {
            // Title screen fallback: when no world is loaded, build skin from config
            if (Minecraft.getInstance().level == null) {
                PlayerSkin fallback = onlineskins$buildTitleScreenFallback(cir.getReturnValue());
                if (fallback != null) {
                    cir.setReturnValue(fallback);
                    return;
                }
            }
            onlineskins$cachedSkin = null;
            onlineskins$cachedOriginalTexture = null;
            return;
        }

        PlayerSkin original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        //? if <1.21.9 {
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;
        ResourceLocation currentElytraLocation = hasSeparateElytra ? service.getSeparateElytraLocation(this.profile.getId()) : null;
        //?} else if <1.21.11 {
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;
        ResourceLocation currentElytraLocation = hasSeparateElytra ? service.getSeparateElytraLocation(this.profile.id()) : null;
        //?} else {
        Identifier currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.id()) : null;
        Identifier currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.id()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.id()) : null;
        Identifier currentElytraLocation = hasSeparateElytra ? service.getSeparateElytraLocation(this.profile.id()) : null;
        //?}

        if (onlineskins$cachedSkin != null &&
            //? if <1.21.9 {
            java.util.Objects.equals(onlineskins$cachedOriginalTexture, original.texture()) &&
            //?} else {
            java.util.Objects.equals(onlineskins$cachedOriginalTexture, original.body().texturePath()) &&
            //?}
            java.util.Objects.equals(onlineskins$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(onlineskins$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(onlineskins$cachedElytraLocation, currentElytraLocation) &&
            java.util.Objects.equals(onlineskins$cachedModelName, currentModelName)) {
            cir.setReturnValue(onlineskins$cachedSkin);
            return;
        }

        //? if <1.21.9 {
        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();
        ResourceLocation elytraTexture = original.elytraTexture();
        //?} else if <1.21.11 {
        ResourceLocation skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        ResourceLocation capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ClientAsset.Texture elytraTexture = original.elytra();
        //?} else {
        Identifier skinTexture = original.body().texturePath();
        PlayerModelType skinModel = original.model();
        Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
        ClientAsset.Texture elytraTexture = original.elytra();
        //?}

        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            //? if <1.21.9 {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            //?} else {
            skinModel = "slim".equals(currentModelName) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            //?}
        }

        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                // An active Quick Skin cape owns the profile Elytra input too; vanilla gives that
                // dedicated field priority and would otherwise keep only the worn wings vanilla.
                //? if <1.21.9 {
                elytraTexture = currentCapeLocation;
                //?} else {
                elytraTexture = new ClientAsset.ResourceTexture(
                        currentCapeLocation, currentCapeLocation);
                //?}
            } else {
                // Pending network animations intentionally resolve to null until their bounded
                // first-frame texture exists. Never publish the stacked atlas to other mods or
                // leave an unrelated profile Elytra beside a pending custom cape.
                capeTexture = null;
                elytraTexture = null;
            }
        }

        if (currentElytraLocation != null) {
            // The elytra wear another cape, or vanilla's elytra, instead of the cape
            //? if <1.21.9 {
            elytraTexture = currentElytraLocation;
            //?} else {
            elytraTexture = new ClientAsset.ResourceTexture(currentElytraLocation, currentElytraLocation);
            //?}
        }

        PlayerSkin customSkin = new PlayerSkin(
            //? if <1.21.9 {
            skinTexture,
            original.textureUrl(),
            capeTexture,
            elytraTexture,
            //?} else {
            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture,
            //?}
            skinModel,
            original.secure()
        );

        onlineskins$cachedSkin = customSkin;
        //? if <1.21.9 {
        onlineskins$cachedOriginalTexture = original.texture();
        //?} else {
        onlineskins$cachedOriginalTexture = original.body().texturePath();
        //?}
        onlineskins$cachedSkinLocation = currentSkinLocation;
        onlineskins$cachedCapeLocation = currentCapeLocation;
        onlineskins$cachedElytraLocation = currentElytraLocation;
        onlineskins$cachedModelName = currentModelName;

        cir.setReturnValue(customSkin);
    }

    /**
     * Builds a PlayerSkin from saved config for title screen fallback.
     * Returns null if no saved skin/cape is configured.
     */
    @Unique
    private PlayerSkin onlineskins$buildTitleScreenFallback(PlayerSkin original) {
        ClientConfig config = ClientConfig.getInstance();
        boolean hasSkin = !config.activeSkinHash.isEmpty();
        boolean hasCape = !config.activeCapeHash.isEmpty();
        //? if <1.21.11 {
        ResourceLocation configuredElytra = PlayerAppearanceService.getInstance().getConfiguredElytraLocation();
        //?} else {
        Identifier configuredElytra = PlayerAppearanceService.getInstance().getConfiguredElytraLocation();
        //?}

        if (!hasSkin && !hasCape && configuredElytra == null) {
            return null;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        //? if <1.21.9 {
        ResourceLocation skinTexture = null;
        PlayerSkin.Model skinModel = (original != null) ? original.model() : PlayerSkin.Model.WIDE;
        ResourceLocation capeTexture = null;
        ResourceLocation elytraTexture = null;
        //?} else if <1.21.11 {
        ResourceLocation skinTexture = null;
        PlayerModelType skinModel = (original != null) ? original.model() : PlayerModelType.WIDE;
        ResourceLocation capeTexture = null;
        ClientAsset.Texture elytraTexture = null;
        //?} else {
        Identifier skinTexture = null;
        PlayerModelType skinModel = (original != null) ? original.model() : PlayerModelType.WIDE;
        Identifier capeTexture = null;
        ClientAsset.Texture elytraTexture = null;
        //?}

        if (hasSkin) {
            skinTexture = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
            if ("auto".equals(modelType)) {
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    modelType = metadata.skinModel();
                }
            }
            //? if <1.21.9 {
            skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            //?} else {
            skinModel = "slim".equals(modelType) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
            //?}
        }

        if (hasCape) {
            capeTexture = com.onlineskins.mod.client.services.CapeService.getInstance()
                    .getCapeLocation(null, config.activeCapeHash);
            if (capeTexture != null) {
                //? if <1.21.9 {
                elytraTexture = capeTexture;
                //?} else {
                elytraTexture = new ClientAsset.ResourceTexture(capeTexture, capeTexture);
                //?}
            }
        }

        if (configuredElytra != null) {
            //? if <1.21.9 {
            elytraTexture = configuredElytra;
            //?} else {
            elytraTexture = new ClientAsset.ResourceTexture(configuredElytra, configuredElytra);
            //?}
        }

        if (skinTexture == null && capeTexture == null && configuredElytra == null) {
            return null;
        }

        if (original != null) {
            //? if >=1.21.9 {
            ClientAsset.Texture skinAsset = skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : original.body();
            ClientAsset.Texture capeAsset = capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : original.cape();
            //?}
            return new PlayerSkin(
                //? if <1.21.9 {
                skinTexture != null ? skinTexture : original.texture(),
                original.textureUrl(),
                capeTexture != null ? capeTexture : original.capeTexture(),
                capeTexture != null || configuredElytra != null ? elytraTexture : original.elytraTexture(),
                //?} else {
                skinAsset,
                capeAsset,
                capeTexture != null || configuredElytra != null ? elytraTexture : original.elytra(),
                //?}
                skinModel,
                original.secure()
            );
        }

        // Last resort: build with just our textures
        return new PlayerSkin(
            //? if <1.21.9 {
            skinTexture, null, capeTexture, elytraTexture, skinModel, false
            //?} else {
            skinTexture != null ? new ClientAsset.ResourceTexture(skinTexture, skinTexture) : null,
            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
            elytraTexture, skinModel, false
            //?}
        );
    }
}
//?}
