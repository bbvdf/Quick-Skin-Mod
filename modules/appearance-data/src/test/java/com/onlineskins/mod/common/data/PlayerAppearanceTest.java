package com.onlineskins.mod.common.data;

import com.onlineskins.mod.platform.TextureReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAppearanceTest {
    @Test
    void changingOneAssetInvalidatesOnlyItsResolvedTexture() {
        PlayerAppearance appearance = new PlayerAppearance(UUID.randomUUID(), "skin-a", "cape-a", "classic");
        TextureReference skin = new Texture("onlineskins", "skin/a");
        TextureReference cape = new Texture("onlineskins", "cape/a");
        appearance.setSkinLocation(skin);
        appearance.setCapeLocation(cape);

        appearance.setSkinId("skin-b");
        assertNull(appearance.getSkinLocation());
        assertSame(cape, appearance.getCapeLocation());

        appearance.setSkinLocation(skin);
        appearance.setCapeId("cape-b");
        assertSame(skin, appearance.getSkinLocation());
        assertNull(appearance.getCapeLocation());
    }

    @Test
    void theElytraWearTheCapeUnlessTheyWearAnotherOne() {
        PlayerAppearance appearance = new PlayerAppearance(UUID.randomUUID(), "", "cape-a", "classic");
        assertEquals("", appearance.getElytraId());
        assertFalse(appearance.hasSeparateElytra());

        appearance.setElytraId("cape-a");
        assertFalse(appearance.hasSeparateElytra());

        appearance.setElytraId(PlayerAppearance.VANILLA_ELYTRA);
        assertTrue(appearance.hasSeparateElytra());

        appearance.setElytraId(null);
        assertEquals("", appearance.getElytraId());
    }

    @Test
    void changingTheElytraInvalidatesOnlyTheirTexture() {
        PlayerAppearance appearance = new PlayerAppearance(UUID.randomUUID(), "skin-a", "cape-a", "cape-b", "classic");
        TextureReference cape = new Texture("onlineskins", "cape/a");
        TextureReference elytra = new Texture("onlineskins", "cape/b");
        appearance.setCapeLocation(cape);
        appearance.setElytraLocation(elytra);

        appearance.setElytraId("cape-c");
        assertNull(appearance.getElytraLocation());
        assertSame(cape, appearance.getCapeLocation());
    }

    private record Texture(String namespace, String path) implements TextureReference {
    }
}
