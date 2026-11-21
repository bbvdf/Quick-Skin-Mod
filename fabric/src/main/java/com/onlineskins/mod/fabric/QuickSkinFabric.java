package com.onlineskins.mod.fabric;

import com.onlineskins.mod.QuickSkin;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point for QuickSkin
 * This class is only loaded on Fabric
 */
public class QuickSkinFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Initialize common code
        QuickSkin.init();
    }
}
