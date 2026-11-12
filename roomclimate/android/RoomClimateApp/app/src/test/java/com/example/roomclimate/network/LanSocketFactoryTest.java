package com.example.roomclimate.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LanSocketFactoryTest {
    @Test
    public void privateAddressesAreLocal() {
        assertTrue(LanSocketFactory.isLocalHost("192.168.0.101"));
        assertTrue(LanSocketFactory.isLocalHost("10.0.2.2"));
        assertTrue(LanSocketFactory.isLocalHost("172.20.1.5"));
        assertTrue(LanSocketFactory.isLocalHost("raspberrypi.local"));
    }

    @Test
    public void publicAddressesAreNotLocal() {
        assertFalse(LanSocketFactory.isLocalHost("8.8.8.8"));
        assertFalse(LanSocketFactory.isLocalHost("172.32.0.1"));
        assertFalse(LanSocketFactory.isLocalHost("example.com"));
        assertFalse(LanSocketFactory.isLocalHost(""));
    }

    @Test
    public void hostIsExtractedFromBaseUrl() {
        assertEquals("192.168.0.101", LanSocketFactory.hostOf("http://192.168.0.101:8000"));
        assertEquals("", LanSocketFactory.hostOf("не адрес"));
    }
}
