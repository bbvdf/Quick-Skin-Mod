package com.onlineskins.mod.client.services;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McSkinsCatalogServiceTest {

    @Test
    void everyFurtherPageFailingIsExceptional() {
        var service = new McSkinsCatalogService(url ->
                url.contains("/page/") ? fail() : cards("first", 24));

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> service.fetchPage(1, McSkinsCatalogService.Window.MONTH).get(5, TimeUnit.SECONDS));

        assertInstanceOf(ElyByCatalogService.CatalogException.class, rootCause(thrown),
                "a listing that never arrived is an error, not an empty page");
    }

    @Test
    void aFailedPageKeepsThePagesReadBeforeIt() throws Exception {
        var service = new McSkinsCatalogService(url -> {
            if (url.endsWith("page/2")) {
                return fail();
            }
            if (url.contains("/page/")) {
                // These would follow the gap; the gap makes them non-contiguous with the opening.
                return cards("after-gap", 24);
            }
            return cards("opening", 24);
        });

        var page = service.fetchPage(1, McSkinsCatalogService.Window.MONTH).get(5, TimeUnit.SECONDS);

        assertEquals(24, page.items.size(), "the page before the gap still fills the grid");
        assertEquals(2, page.lastPage, "a gap never claims the window is finished");
        assertTrue(page.items.stream().noneMatch(item -> item.skinUrl.contains("after-gap")),
                "cards read past a gap are not stitched into the page");
    }

    @Test
    void aShortOpeningPageEndsTheWindow() throws Exception {
        var service = new McSkinsCatalogService(url -> cards("short", 3));

        var page = service.fetchPage(1, McSkinsCatalogService.Window.MONTH).get(5, TimeUnit.SECONDS);

        assertEquals(3, page.items.size());
        assertEquals(1, page.lastPage, "a window that fits in one page ends there");
    }

    private static List<String> fail() {
        throw new IllegalStateException("offline");
    }

    private static List<String> cards(String prefix, int count) {
        List<String> cards = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            cards.add(prefix + "-" + index);
        }
        return cards;
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
