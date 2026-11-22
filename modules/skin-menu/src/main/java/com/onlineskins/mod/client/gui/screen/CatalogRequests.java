package com.onlineskins.mod.client.gui.screen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main-thread bookkeeping for the catalog grid: which page a callback still belongs to, which
 * faces are downloading, and how often a failed tile may be tried again.
 * <p>
 * Every page, ordering or catalog change starts a new generation, so a download that finishes
 * after the reader has moved on is recognised as stale and dropped instead of registering a
 * texture for a page nobody is looking at. A face that fails is retried a bounded number of
 * times with a pause between attempts, so one transient network or queue failure no longer
 * leaves its tile empty for the rest of the visit, and a failing tile cannot spin the frame loop.
 */
final class CatalogRequests {
    /** How often one entry's texture may fail on one page before its tile stays empty. */
    static final int MAX_FACE_ATTEMPTS = 3;
    /** The pause before a failed entry is asked for again. */
    static final long FACE_RETRY_DELAY_NANOS = 1_000_000_000L;

    private long generation;
    private int pageRequestId;
    private final Set<Long> inFlight = new HashSet<>();
    private final Map<Long, Attempt> failures = new HashMap<>();

    /** Starts a new page or source; every callback of the previous one is stale from here. */
    int beginPage() {
        generation++;
        inFlight.clear();
        failures.clear();
        return ++pageRequestId;
    }

    /** Invalidates the textures of the current page without asking for a new one. */
    long invalidateFaces() {
        generation++;
        inFlight.clear();
        failures.clear();
        return generation;
    }

    boolean isCurrentPage(int request) {
        return request == pageRequestId;
    }

    long generation() {
        return generation;
    }

    boolean isCurrentGeneration(long value) {
        return value == generation;
    }

    /** Claims an entry for a download; false while it is in flight or out of attempts. */
    boolean beginFace(long id, long nowNanos) {
        if (!inFlight.add(id)) {
            return false;
        }
        Attempt previous = failures.get(id);
        if (previous != null
                && (previous.attempts >= MAX_FACE_ATTEMPTS || nowNanos - previous.retryAfterNanos < 0)) {
            inFlight.remove(id);
            return false;
        }
        return true;
    }

    void finishFace(long id) {
        inFlight.remove(id);
        failures.remove(id);
    }

    /** Records a failed download and closes the entry until its next retry. */
    void failFace(long id, long nowNanos) {
        inFlight.remove(id);
        Attempt previous = failures.get(id);
        int attempts = previous == null ? 1 : previous.attempts + 1;
        failures.put(id, new Attempt(attempts, nowNanos + FACE_RETRY_DELAY_NANOS));
    }

    /** Whether a failed entry may be asked for again now, which the draw loop checks per frame. */
    boolean retryDue(long id, long nowNanos) {
        Attempt failure = failures.get(id);
        return failure != null && failure.attempts < MAX_FACE_ATTEMPTS
                && nowNanos - failure.retryAfterNanos >= 0;
    }

    private record Attempt(int attempts, long retryAfterNanos) {
    }
}
