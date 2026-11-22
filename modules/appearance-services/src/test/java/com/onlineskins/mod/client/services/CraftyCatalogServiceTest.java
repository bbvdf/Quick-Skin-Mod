package com.onlineskins.mod.client.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftyCatalogServiceTest {

    @Test
    void catalogIdKeepsTheWholeSixtyFourBitHash() {
        String leading = "0123456789abcdef";
        assertEquals(0x0123456789abcdefL, CraftyCatalogService.catalogId(leading + "0".repeat(48)));
        assertEquals(-1L, CraftyCatalogService.catalogId("f".repeat(64)));
        assertEquals(Long.MIN_VALUE, CraftyCatalogService.catalogId("8" + "0".repeat(63)));
    }

    @Test
    void catalogIdIsStableAndDistinguishesSkins() {
        String first = "a".repeat(64);
        String second = "b".repeat(64);

        assertEquals(CraftyCatalogService.catalogId(first), CraftyCatalogService.catalogId(first),
                "the same hash always yields the same grid id");
        assertNotEquals(CraftyCatalogService.catalogId(first), CraftyCatalogService.catalogId(second));
    }

    @Test
    void catalogIdFollowsTheFirstSixteenHexDigits() {
        String prefix = "00112233445566aa";
        assertEquals(CraftyCatalogService.catalogId(prefix + "0".repeat(48)),
                CraftyCatalogService.catalogId(prefix + "f".repeat(48)),
                "only the first sixteen digits identify the entry; the rest is not folded in");
    }
}
