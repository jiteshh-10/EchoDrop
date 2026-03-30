package com.dev.echodrop.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for {@link BlockedDeviceStore} normalization and self-block protections.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BlockedDeviceStoreTest {

    private static final String PREFS_BLOCKLIST = "echodrop_prefs";
    private static final String PREFS_DEVICE = "echodrop_device";
    private static final String KEY_BLOCKED_IDS = "blocked_device_ids";
    private static final String KEY_DEVICE_ID = "device_id";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFS_BLOCKLIST, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void addBlockedId_rejectsSelf_caseInsensitive() {
        setLocalId("abc12345");

        assertFalse(BlockedDeviceStore.addBlockedId(context, "ABC12345"));
        assertFalse(BlockedDeviceStore.isBlocked(context, "abc12345"));
    }

    @Test
    public void isBlocked_matchesCaseInsensitiveFromStoredSet() {
        setLocalId("local000");
        setRawBlocked("DEADBEEF");

        assertTrue(BlockedDeviceStore.isBlocked(context, "deadbeef"));
        assertTrue(BlockedDeviceStore.isBlocked(context, "DEADBEEF"));
    }

    @Test
    public void getBlockedIds_excludesLocalIdFromLegacyStoredData() {
        setLocalId("facefeed");
        setRawBlocked("FACEFEED", "cafebabe");

        final Set<String> blocked = BlockedDeviceStore.getBlockedIds(context);
        assertEquals(1, blocked.size());
        assertTrue(blocked.contains("cafebabe"));
    }

    private void setLocalId(String id) {
        context.getSharedPreferences(PREFS_DEVICE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE_ID, id)
                .commit();
    }

    private void setRawBlocked(String... ids) {
        final Set<String> blocked = new HashSet<>(Arrays.asList(ids));
        context.getSharedPreferences(PREFS_BLOCKLIST, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED_IDS, blocked)
                .commit();
    }
}
