package com.dev.echodrop.service;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

/**
 * Unit tests for {@link EchoService} static preference helpers and
 * {@link BootReceiver} logic.
 *
 * Tests cover:
 * - Background enabled preference defaults
 * - Set and get preference round-trip
 * - BootReceiver null intent handling
 * - BootReceiver null action handling
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class EchoServiceTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Clear preferences before each test
        context.getSharedPreferences("echodrop_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    // ── Preference Defaults ───────────────────────────────

    @Test
    public void isBackgroundEnabled_defaultsTrue() {
        assertTrue(EchoService.isBackgroundEnabled(context));
    }

    // ── Set + Get Round-Trip ──────────────────────────────

    @Test
    public void setBackgroundEnabled_false_persistsCorrectly() {
        EchoService.setBackgroundEnabled(context, false);
        assertFalse(EchoService.isBackgroundEnabled(context));
    }

    @Test
    public void setBackgroundEnabled_true_persistsCorrectly() {
        EchoService.setBackgroundEnabled(context, false);
        EchoService.setBackgroundEnabled(context, true);
        assertTrue(EchoService.isBackgroundEnabled(context));
    }

    @Test
    public void setBackgroundEnabled_multipleToggles() {
        for (int i = 0; i < 5; i++) {
            boolean expected = (i % 2 == 0);
            EchoService.setBackgroundEnabled(context, expected);
            assertEquals("Iteration " + i, expected, EchoService.isBackgroundEnabled(context));
        }
    }

    // ── BootReceiver Null Safety ──────────────────────────

    @Test
    public void bootReceiver_nullIntent_doesNotCrash() {
        final BootReceiver receiver = new BootReceiver();
        receiver.onReceive(context, null);
        // No crash = pass
    }

    @Test
    public void bootReceiver_nullAction_doesNotCrash() {
        final BootReceiver receiver = new BootReceiver();
        final android.content.Intent intent = new android.content.Intent();
        receiver.onReceive(context, intent);
        // No crash = pass
    }

    @Test
    public void bootReceiver_nonBootAction_doesNothing() {
        EchoService.setBackgroundEnabled(context, true);
        final BootReceiver receiver = new BootReceiver();
        final android.content.Intent intent = new android.content.Intent("com.example.OTHER");
        receiver.onReceive(context, intent);
        // Should not crash; service start will fail gracefully in test environment
    }

    // ── SharedPreferences Isolation ───────────────────────

    @Test
    public void preferences_useCorrectPrefsName() {
        EchoService.setBackgroundEnabled(context, false);

        final SharedPreferences prefs =
                context.getSharedPreferences("echodrop_prefs", Context.MODE_PRIVATE);
        assertFalse(prefs.getBoolean("bg_enabled", true));
    }
}
