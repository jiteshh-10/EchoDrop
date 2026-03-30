package com.dev.echodrop.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Tests permission helper behavior across Android API levels.
 */
@RunWith(RobolectricTestRunner.class)
public class EchoServicePermissionTest {

    @Test
    @Config(sdk = 30, manifest = Config.NONE)
    public void getBlePermissions_android11_requiresLocationOnly() {
        final String[] expected = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        assertArrayEquals(expected, EchoService.getBlePermissions());
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void getBlePermissions_android13_requiresBtAndLocation() {
        final String[] expected = new String[]{
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        assertArrayEquals(expected, EchoService.getBlePermissions());
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void hasBlePermissions_missingAnyPermission_returnsFalse() {
        final Application app = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(app).denyPermissions(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        );

        assertFalse(EchoService.hasBlePermissions(app));
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void hasBlePermissions_allGranted_returnsTrue() {
        final Application app = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(app).grantPermissions(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
        );

        assertTrue(EchoService.hasBlePermissions(app));
    }
}
