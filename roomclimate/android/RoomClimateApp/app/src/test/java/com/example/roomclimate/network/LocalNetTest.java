package com.example.roomclimate.network;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalNetTest {
    @Test
    public void parsesIpv4() {
        assertEquals(0xC0A80065L, LocalNet.toLong("192.168.0.101"));
        assertEquals("10.72.68.104", LocalNet.toIp(LocalNet.toLong("10.72.68.104")));
        assertEquals(-1, LocalNet.toLong("256.1.1.1"));
        assertEquals(-1, LocalNet.toLong("1.2.3"));
        assertEquals(-1, LocalNet.toLong("a.b.c.d"));
        assertEquals(-1, LocalNet.toLong(null));
    }

    @Test
    public void subnetMembership() {
        // Раздача с телефона: телефон 10.72.68.104/24, компьютер 10.72.68.167.
        assertTrue(LocalNet.inSubnet("10.72.68.167", "10.72.68.104", 24));
        assertFalse(LocalNet.inSubnet("10.72.69.1", "10.72.68.104", 24));
        assertTrue(LocalNet.inSubnet("192.168.5.7", "192.168.0.1", 16));
        assertFalse(LocalNet.inSubnet("8.8.8.8", "192.168.0.1", 16));
    }

    @Test
    public void privateRanges() {
        assertTrue(LocalNet.isPrivate("10.1.2.3"));
        assertTrue(LocalNet.isPrivate("172.31.0.1"));
        assertTrue(LocalNet.isPrivate("192.168.43.1"));
        assertFalse(LocalNet.isPrivate("172.32.0.1"));
        assertFalse(LocalNet.isPrivate("8.8.8.8"));
        assertFalse(LocalNet.isPrivate("example.com"));
    }

    @Test
    public void tetherAndIgnoredInterfaces() {
        assertTrue(LocalNet.isTetherInterface("ap0"));
        assertTrue(LocalNet.isTetherInterface("swlan0"));
        assertTrue(LocalNet.isTetherInterface("wlan1"));
        assertTrue(LocalNet.isTetherInterface("rndis0"));
        assertFalse(LocalNet.isTetherInterface("wlan0"));
        assertFalse(LocalNet.isTetherInterface("rmnet_data0"));

        assertTrue(LocalNet.isIgnoredInterface("tun0"));
        assertTrue(LocalNet.isIgnoredInterface("rmnet_data1"));
        assertTrue(LocalNet.isIgnoredInterface("wg0"));
        assertFalse(LocalNet.isIgnoredInterface("ap0"));
        assertFalse(LocalNet.isIgnoredInterface("wlan0"));
    }

    @Test
    public void scanTargetsCoverSubnetGatewayFirst() {
        List<String> targets = LocalNet.scanTargets("10.72.68.104", 24, "10.72.68.1");
        assertEquals("10.72.68.1", targets.get(0));
        assertEquals(253, targets.size()); // 254 хоста минус свой адрес
        assertFalse(targets.contains("10.72.68.104"));
        assertFalse(targets.contains("10.72.68.0"));
        assertFalse(targets.contains("10.72.68.255"));
        assertTrue(targets.contains("10.72.68.167"));
    }

    @Test
    public void wideSubnetIsLimitedToSlash24() {
        List<String> targets = LocalNet.scanTargets("192.168.5.20", 16, null);
        assertEquals(253, targets.size());
        assertTrue(targets.contains("192.168.5.1"));
        assertFalse(targets.contains("192.168.4.1"));
    }

    @Test
    public void hostOfUrl() {
        assertEquals("10.72.68.167", LocalNet.hostOf("http://10.72.68.167:8000"));
        assertEquals("", LocalNet.hostOf("не адрес"));
    }
}
