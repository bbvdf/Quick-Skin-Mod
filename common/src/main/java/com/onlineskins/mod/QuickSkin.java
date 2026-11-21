package com.onlineskins.mod;

import com.onlineskins.mod.event.CommonEvents;
import com.onlineskins.mod.networking.NetworkTransport;
import com.onlineskins.mod.runtime.ServerRuntime;
import com.onlineskins.mod.platform.QuickSkinInfo;
import org.slf4j.Logger;

/**
 * Main entry point for QuickSkin mod (common initialization)
 * This runs on both Fabric and Forge
 */
public class QuickSkin {
    public static final String MOD_ID = QuickSkinInfo.MOD_ID;
    public static final String MOD_NAME = QuickSkinInfo.MOD_NAME;
    public static final Logger LOGGER = QuickSkinInfo.LOGGER;

    private static final ServerRuntime SERVER_RUNTIME = ServerRuntime.getInstance();
    private static boolean initialized;

    /**
     * Common initialization - runs on both client and server
     * Called from platform-specific entry points (Forge @Mod, Fabric ModInitializer)
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }

        // Configuration must exist before network handlers and lifecycle callbacks can observe it.
        com.onlineskins.mod.config.ServerConfig.getInstance();

        // Register networking (server-side receivers).
        NetworkTransport.INSTANCE.init();

        // Register common events against the process-owned server runtime.
        CommonEvents.init(SERVER_RUNTIME);
        initialized = true;
    }

    /**
     * Releases transient server state when an embedding platform has an explicit shutdown hook.
     * Normal game shutdown is also handled by the server lifecycle callbacks.
     */
    public static void close() {
        SERVER_RUNTIME.close();
    }
}
