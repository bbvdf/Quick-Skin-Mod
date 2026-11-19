package com.quickskin.mod.client.services;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ElyByCatalogServiceTest {

    @Test
    void pageParsesItemsAndPaging() throws Exception {
        String body = "{\"items\":["
                + "{\"id\":1,\"skin_url\":\"http://ely.by/storage/skins/" + "a".repeat(32)
                + ".png\",\"is_slim\":true,\"tags\":[\"anime\"]},"
                // A foreign host must be dropped rather than handed to the renderer.
                + "{\"id\":2,\"skin_url\":\"https://evil.example/skin.png\",\"is_slim\":false},"
                + "{\"id\":3,\"skin_url\":\"https://ely.by/storage/skins/" + "b".repeat(32)
                + ".png\",\"is_slim\":false,\"tags\":[]}"
                + "],\"total_items\":10000,\"current\":3,\"last\":250}";
        var transport = new Transport(new Reply(200, utf8(body)));

        var page = transport.service.fetchPage(3).get(5, TimeUnit.SECONDS);

        assertEquals("https://ely.by/api/legacy/skins?page=3", transport.urls.get(0));
        assertEquals(3, page.page);
        assertEquals(250, page.lastPage);
        assertEquals(10000, page.totalItems);
        assertEquals(2, page.items.size(), "the foreign host is skipped, the rest of the page stays");
        assertEquals(1, page.items.get(0).id);
        assertTrue(page.items.get(0).slim);
        assertEquals("anime", page.items.get(0).firstTag);
        assertEquals("https://ely.by/storage/skins/" + "a".repeat(32) + ".png",
                page.items.get(0).skinUrl, "http storage URLs are upgraded to https");
        assertEquals(3, page.items.get(1).id);
        assertEquals("", page.items.get(1).firstTag);
    }

    @Test
    void newestOrderRequestsTheTimeSort() throws Exception {
        var transport = new Transport(new Reply(200, utf8(
                "{\"items\":[],\"total_items\":0,\"current\":1,\"last\":1}")));
        transport.service.fetchPage(2, true).get(5, TimeUnit.SECONDS);
        assertEquals("https://ely.by/api/legacy/skins?page=2&sort=time", transport.urls.get(0),
                "the catalog only sorts when asked with its own sort=time");
    }

    @Test
    void emptyPageIsAnEmptyList() throws Exception {
        var transport = new Transport(new Reply(200, utf8(
                "{\"items\":[],\"total_items\":0,\"current\":1,\"last\":1}")));
        var page = transport.service.fetchPage(1).get(5, TimeUnit.SECONDS);
        assertTrue(page.items.isEmpty());
        assertEquals(1, page.lastPage);
    }

    @Test
    void httpFailureIsExceptional() {
        var transport = new Transport(new Reply(500, utf8("upstream failure")));
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> transport.service.fetchPage(1).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ElyByCatalogService.CatalogException.class, thrown.getCause());
        assertFalse(String.valueOf(thrown.getCause().getMessage()).contains("upstream failure"),
                "response bodies never reach the exception message");
    }

    @Test
    void malformedResponseIsExceptional() {
        var transport = new Transport(new Reply(200, utf8("{}")));
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> transport.service.fetchPage(1).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ElyByCatalogService.CatalogException.class, thrown.getCause());
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Reply {
        final int status;
        final byte[] body;

        Reply(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class Transport {
        final List<String> urls = new ArrayList<>();
        final ElyByCatalogService service;

        Transport(Reply reply) {
            this.service = new ElyByCatalogService(url -> {
                urls.add(url.toString());
                return new Connection(url, reply);
            });
        }
    }

    private static final class Connection extends HttpURLConnection {
        private final Reply reply;
        boolean disconnected;

        Connection(URL url, Reply reply) {
            super(url);
            this.reply = reply;
        }

        @Override public int getResponseCode() { return reply.status; }
        @Override public long getContentLengthLong() { return reply.body.length; }

        @Override public InputStream getInputStream() {
            return new FilterInputStream(new ByteArrayInputStream(reply.body)) {};
        }

        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
