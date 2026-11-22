package com.onlineskins.mod.client.gui.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogRequestsTest {

    @Test
    void oneFaceIsOnlyClaimedOnce() {
        CatalogRequests requests = new CatalogRequests();

        assertTrue(requests.beginFace(7L, 0L));
        assertFalse(requests.beginFace(7L, 0L), "a face already downloading is not asked for again");
        requests.finishFace(7L);
        assertTrue(requests.beginFace(7L, 0L), "a finished face may be claimed for its next use");
    }

    @Test
    void aFailedFaceWaitsBeforeItsRetry() {
        CatalogRequests requests = new CatalogRequests();
        requests.beginFace(3L, 0L);
        requests.failFace(3L, 0L);

        assertFalse(requests.beginFace(3L, CatalogRequests.FACE_RETRY_DELAY_NANOS - 1L),
                "a retry does not start before the pause is over");
        assertFalse(requests.retryDue(3L, CatalogRequests.FACE_RETRY_DELAY_NANOS - 1L));
        assertTrue(requests.retryDue(3L, CatalogRequests.FACE_RETRY_DELAY_NANOS));
        assertTrue(requests.beginFace(3L, CatalogRequests.FACE_RETRY_DELAY_NANOS));
        requests.finishFace(3L);
        assertFalse(requests.retryDue(3L, CatalogRequests.FACE_RETRY_DELAY_NANOS),
                "a tile that succeeded on retry is no longer due for another try");
    }

    @Test
    void attemptsAreBounded() {
        CatalogRequests requests = new CatalogRequests();
        long now = 0L;
        for (int attempt = 0; attempt < CatalogRequests.MAX_FACE_ATTEMPTS; attempt++) {
            assertTrue(requests.beginFace(9L, now), "attempt " + attempt + " is still allowed");
            requests.failFace(9L, now);
            now += CatalogRequests.FACE_RETRY_DELAY_NANOS;
        }

        assertFalse(requests.retryDue(9L, now + CatalogRequests.FACE_RETRY_DELAY_NANOS));
        assertFalse(requests.beginFace(9L, now + CatalogRequests.FACE_RETRY_DELAY_NANOS),
                "one tile cannot retry forever");
    }

    @Test
    void aNewPageInvalidatesOlderCallbacksAndRetries() {
        CatalogRequests requests = new CatalogRequests();
        int firstPage = requests.beginPage();
        long firstGeneration = requests.generation();
        requests.beginFace(1L, 0L);
        requests.failFace(1L, 0L);

        int secondPage = requests.beginPage();

        assertNotEquals(firstPage, secondPage);
        assertFalse(requests.isCurrentPage(firstPage), "a page request from before the change is stale");
        assertTrue(requests.isCurrentPage(secondPage));
        assertFalse(requests.isCurrentGeneration(firstGeneration));
        assertTrue(requests.beginFace(1L, 0L), "a new page gives every face a fresh set of attempts");
    }

    @Test
    void switchingCatalogInvalidatesFacesWithoutANewRequest() {
        CatalogRequests requests = new CatalogRequests();
        int page = requests.beginPage();
        long generation = requests.generation();

        long changed = requests.invalidateFaces();

        assertNotEquals(generation, changed);
        assertTrue(requests.isCurrentPage(page), "the page request itself is still the current one");
        assertFalse(requests.isCurrentGeneration(generation));
    }
}
