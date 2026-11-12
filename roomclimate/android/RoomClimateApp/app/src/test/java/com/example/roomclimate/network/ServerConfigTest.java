package com.example.roomclimate.network;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerConfigTest {
    @Test
    public void plainHttpOnlyInsideLocalNetwork() {
        assertTrue(ServerConfig.isAllowedBaseUrl("http://192.168.1.14:8000"));
        assertTrue(ServerConfig.isAllowedBaseUrl("http://10.72.68.167:8000/"));
        assertTrue(ServerConfig.isAllowedBaseUrl("http://10.0.2.2:8000"));
        assertTrue(ServerConfig.isAllowedBaseUrl("http://127.0.0.1:8000"));
        assertTrue(ServerConfig.isAllowedBaseUrl("http://raspberrypi.local:8000"));
        assertFalse(ServerConfig.isAllowedBaseUrl("http://example.com:8000"));
        assertFalse(ServerConfig.isAllowedBaseUrl("http://8.8.8.8:8000"));
    }

    @Test
    public void httpsAnywhereOtherSchemesNever() {
        assertTrue(ServerConfig.isAllowedBaseUrl("https://climate.example.com"));
        assertFalse(ServerConfig.isAllowedBaseUrl("ftp://192.168.1.14"));
        assertFalse(ServerConfig.isAllowedBaseUrl("javascript:alert(1)"));
        assertFalse(ServerConfig.isAllowedBaseUrl(""));
        assertFalse(ServerConfig.isAllowedBaseUrl("не адрес"));
    }
}
