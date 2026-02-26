package com.dev.echodrop.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link DeviceIdHelper}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Generated ID format (8-char lowercase hex)</li>
 *   <li>Uniqueness across multiple generations</li>
 *   <li>Non-null guarantee</li>
 * </ul>
 * </p>
 */
public class DeviceIdHelperTest {

    @Test
    public void generateDeviceId_isEightCharHex() {
        final String id = DeviceIdHelper.generateDeviceId();
        assertNotNull(id);
        assertEquals("Device ID should be 8 chars", 8, id.length());
        assertTrue("Device ID should be lowercase hex",
                id.matches("[0-9a-f]{8}"));
    }

    @Test
    public void generateDeviceId_isNonNull() {
        assertNotNull(DeviceIdHelper.generateDeviceId());
    }

    @Test
    public void generateDeviceId_uniqueAcrossCalls() {
        final String id1 = DeviceIdHelper.generateDeviceId();
        final String id2 = DeviceIdHelper.generateDeviceId();
        // Technically could collide but astronomically unlikely
        assertTrue("Two generated IDs should differ (probabilistic)",
                !id1.equals(id2));
    }

    @Test
    public void generateDeviceId_alwaysCorrectLength() {
        // Run multiple times to catch edge cases in UUID → hex substring
        for (int i = 0; i < 100; i++) {
            final String id = DeviceIdHelper.generateDeviceId();
            assertEquals(8, id.length());
        }
    }
}
