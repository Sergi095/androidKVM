package org.androidkvm.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PikvmAddressTest {
    @Test public void defaultsToHttps() throws Exception {
        assertEquals("https://pikvm.local:443", new PikvmClient("pikvm.local", "").baseUrl);
    }

    @Test public void allowsExplicitHttp() throws Exception {
        assertEquals("http://pikvm.local:8080", new PikvmClient("http://pikvm.local:8080", "").baseUrl);
    }
}
