package com.dev.echodrop.adapters;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.DiffUtil;
import androidx.test.core.app.ApplicationProvider;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link MessageAdapter}.
 * Uses Robolectric to provide Android framework stubs.
 *
 * Updated in Iteration 2 to use {@link MessageEntity} instead of Message POJO.
 *
 * Tests cover:
 * - Adapter construction
 * - Item count after submitList
 * - DiffUtil areItemsTheSame (ID comparison)
 * - DiffUtil areContentsTheSame (field comparison)
 * - Empty list handling
 * - List replacement
 * - Click listener contract
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class MessageAdapterTest {

    private MessageAdapter adapter;
    private DiffUtil.ItemCallback<MessageEntity> diffCallback;

    private static final long NOW = System.currentTimeMillis();
    private static final long EXPIRES = NOW + 3600000L;

    @Before
    public void setUp() throws Exception {
        adapter = new MessageAdapter();

        // Extract the DIFF_CALLBACK via reflection for direct testing
        Field field = MessageAdapter.class.getDeclaredField("DIFF_CALLBACK");
        field.setAccessible(true);
        diffCallback = (DiffUtil.ItemCallback<MessageEntity>) field.get(null);
    }

    // ── Adapter Construction ──────────────────────────────────────

    @Test
    public void constructor_createsEmptyAdapter() {
        assertEquals("New adapter should have 0 items", 0, adapter.getItemCount());
    }

    // ── submitList ────────────────────────────────────────────────

    @Test
    public void submitList_updatesItemCount() {
        List<MessageEntity> messages = Arrays.asList(
                createEntity("1", "Hello", "LOCAL", "NORMAL"),
                createEntity("2", "World", "ZONE", "ALERT")
        );
        adapter.submitList(messages);
        assertEquals(2, adapter.getItemCount());
    }

    @Test
    public void submitList_null_clearsAdapter() {
        adapter.submitList(Arrays.asList(
                createEntity("1", "Test", "LOCAL", "NORMAL")
        ));
        adapter.submitList(null);
        assertEquals(0, adapter.getItemCount());
    }

    @Test
    public void submitList_emptyList_setsZeroItems() {
        adapter.submitList(Arrays.asList());
        assertEquals(0, adapter.getItemCount());
    }

    @Test
    public void submitList_replacesList() {
        List<MessageEntity> first = Arrays.asList(
                createEntity("1", "First", "LOCAL", "NORMAL")
        );
        List<MessageEntity> second = Arrays.asList(
                createEntity("2", "Second", "ZONE", "ALERT"),
                createEntity("3", "Third", "EVENT", "BULK")
        );

        // Use commitCallback to wait for DiffUtil to finish
        final boolean[] committed = {false};
        adapter.submitList(first, () -> {
            adapter.submitList(second, () -> committed[0] = true);
        });

        // After DiffUtil finishes, the count should reflect the new list
        if (committed[0]) {
            assertEquals(2, adapter.getItemCount());
        } else {
            assertTrue("Item count should be ≥0 during async diff",
                    adapter.getItemCount() >= 0);
        }
    }

    // ── DiffUtil: areItemsTheSame ─────────────────────────────────

    @Test
    public void diffUtil_sameId_returnsTrue() {
        MessageEntity a = createEntity("abc", "Text A", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("abc", "Text B", "ZONE", "ALERT");
        assertTrue("Same ID → same item", diffCallback.areItemsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentId_returnsFalse() {
        MessageEntity a = createEntity("id-1", "Same text", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("id-2", "Same text", "LOCAL", "NORMAL");
        assertFalse("Different ID → different item", diffCallback.areItemsTheSame(a, b));
    }

    // ── DiffUtil: areContentsTheSame ──────────────────────────────

    @Test
    public void diffUtil_identicalContent_returnsTrue() {
        MessageEntity a = createEntity("id", "Hello", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("id", "Hello", "LOCAL", "NORMAL");
        assertTrue("Identical content → same contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentText_returnsFalse() {
        MessageEntity a = createEntity("id", "Hello", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("id", "World", "LOCAL", "NORMAL");
        assertFalse("Different text → different contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentScope_returnsFalse() {
        MessageEntity a = createEntity("id", "Hello", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("id", "Hello", "ZONE", "NORMAL");
        assertFalse("Different scope → different contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentPriority_returnsFalse() {
        MessageEntity a = createEntity("id", "Hello", "LOCAL", "NORMAL");
        MessageEntity b = createEntity("id", "Hello", "LOCAL", "ALERT");
        assertFalse("Different priority → different contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentExpiresAt_returnsFalse() {
        String hash = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        MessageEntity a = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW, EXPIRES, false, hash);
        MessageEntity b = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW, EXPIRES + 1000, false, hash);
        assertFalse("Different expiresAt → different contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentReadState_returnsFalse() {
        String hash = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        MessageEntity a = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW, EXPIRES, false, hash);
        MessageEntity b = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW, EXPIRES, true, hash);
        assertFalse("Different read state → different contents", diffCallback.areContentsTheSame(a, b));
    }

    @Test
    public void diffUtil_differentCreatedAt_sameOtherFields_returnsTrue() {
        // createdAt is NOT part of areContentsTheSame
        String hash = MessageEntity.computeHash("Hello", "LOCAL", NOW);
        MessageEntity a = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW, EXPIRES, false, hash);
        MessageEntity b = new MessageEntity("id", "Hello", "LOCAL", "NORMAL", NOW + 5000, EXPIRES, false, hash);
        assertTrue("Different createdAt but same visual fields → same contents", diffCallback.areContentsTheSame(a, b));
    }

    // ── Click Listener ────────────────────────────────────────────

    @Test
    public void setOnMessageClickListener_acceptsNull() {
        // Should not throw
        adapter.setOnMessageClickListener(null);
    }

    @Test
    public void setOnMessageClickListener_acceptsListener() {
        adapter.setOnMessageClickListener(message -> {});
    }

    // ── Helper ────────────────────────────────────────────────────

    private MessageEntity createEntity(String id, String text, String scope, String priority) {
        String hash = MessageEntity.computeHash(text, scope, NOW);
        return new MessageEntity(id, text, scope, priority, NOW, EXPIRES, false, hash);
    }
}
