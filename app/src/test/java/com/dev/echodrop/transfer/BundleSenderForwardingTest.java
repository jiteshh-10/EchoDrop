package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dev.echodrop.db.MessageEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for Iteration 7 forwarding logic in {@link BundleSender}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Expired-message filtering in sendForForwarding</li>
 *   <li>Hop-limit filtering</li>
 *   <li>Seen-by-ids filtering (skip messages already seen by peer)</li>
 *   <li>LOCAL scope filtering (only forwarded when isBleSession=true)</li>
 *   <li>Empty forwardable list → onSendComplete(0)</li>
 *   <li>Mixed filtering (all rules applied together)</li>
 * </ul>
 * </p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BundleSenderForwardingTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long ONE_HOUR = 60 * 60 * 1000L;
    private static final String LOCAL_DEVICE = "aabbccdd";
    private static final String PEER_DEVICE = "11223344";

    private BundleSender sender;

    @Before
    public void setUp() {
        sender = new BundleSender();
    }

    @After
    public void tearDown() {
        sender.shutdown();
    }

    private MessageEntity createMsg(String id, String scope, String priority,
                                     long expiresAt, int hopCount, String seenBy) {
        final String hash = MessageEntity.computeHash("Test " + id, scope, NOW);
        final MessageEntity msg = new MessageEntity(id, "Test " + id, scope, priority,
                NOW, expiresAt, false, hash);
        msg.setHopCount(hopCount);
        msg.setSeenByIds(seenBy);
        return msg;
    }

    private MessageEntity activeMsg(String id, String scope) {
        return createMsg(id, scope, "NORMAL", NOW + ONE_HOUR, 0, "");
    }

    // ── Expired filtering ─────────────────────────────────────────

    @Test
    public void sendForForwarding_filtersExpiredMessages() throws InterruptedException {
        final MessageEntity expired = createMsg("expired", "LOCAL", "NORMAL",
                NOW - 1000, 0, "");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.singletonList(expired),
                LOCAL_DEVICE, PEER_DEVICE, true,
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("Expired messages should be filtered", 0, count.get());
    }

    // ── Hop limit filtering ───────────────────────────────────────

    @Test
    public void sendForForwarding_filtersAtHopLimit() throws InterruptedException {
        final MessageEntity atLimit = createMsg("at-limit", "ZONE", "NORMAL",
                NOW + ONE_HOUR, MessageEntity.MAX_HOP_COUNT, "");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.singletonList(atLimit),
                LOCAL_DEVICE, PEER_DEVICE, true,
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("Messages at hop limit should be filtered", 0, count.get());
    }

    // ── Seen-by filtering ─────────────────────────────────────────

    @Test
    public void sendForForwarding_filtersSeenByPeer() throws InterruptedException {
        final MessageEntity seen = createMsg("seen", "ZONE", "NORMAL",
                NOW + ONE_HOUR, 1, PEER_DEVICE);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.singletonList(seen),
                LOCAL_DEVICE, PEER_DEVICE, true,
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("Messages seen by peer should be filtered", 0, count.get());
    }

    // ── LOCAL scope + non-BLE session filtering ───────────────────

    @Test
    public void sendForForwarding_filtersLocalScopeWhenNotBle() throws InterruptedException {
        final MessageEntity localMsg = activeMsg("local-only", "LOCAL");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.singletonList(localMsg),
                LOCAL_DEVICE, PEER_DEVICE, false, // NOT a BLE session
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("LOCAL scope should be filtered when not BLE session", 0, count.get());
    }

    // ── Empty list ────────────────────────────────────────────────

    @Test
    public void sendForForwarding_emptyList_completesWithZero() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.emptyList(),
                LOCAL_DEVICE, PEER_DEVICE, true,
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, count.get());
    }

    // ── All filtered out ──────────────────────────────────────────

    @Test
    public void sendForForwarding_allFilteredOut_completesWithZero() throws InterruptedException {
        // All messages should be filtered for different reasons
        final List<MessageEntity> messages = Arrays.asList(
                createMsg("expired", "ZONE", "NORMAL", NOW - 1000, 0, ""),          // expired
                createMsg("hop-limit", "ZONE", "NORMAL", NOW + ONE_HOUR,
                        MessageEntity.MAX_HOP_COUNT, ""),                             // at hop limit
                createMsg("seen", "ZONE", "NORMAL", NOW + ONE_HOUR, 1, PEER_DEVICE), // seen by peer
                createMsg("local-no-ble", "LOCAL", "NORMAL", NOW + ONE_HOUR, 0, "")  // LOCAL, no BLE
        );

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                messages,
                LOCAL_DEVICE, PEER_DEVICE, false, // no BLE → LOCAL filtered
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("All messages should be filtered out", 0, count.get());
    }

    // ── ZONE scope passes when not BLE ────────────────────────────

    @Test
    public void sendForForwarding_zoneScope_passesWhenNotBle() throws InterruptedException {
        // ZONE scope should pass even without BLE session — will fail to connect
        // but that's OK, we just check the filter doesn't block it
        final MessageEntity zoneMsg = activeMsg("zone-1", "ZONE");

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicInteger count = new AtomicInteger(-1);

        sender.sendForForwarding(InetAddress.getLoopbackAddress(),
                Collections.singletonList(zoneMsg),
                LOCAL_DEVICE, PEER_DEVICE, false, // not BLE, but ZONE scope should pass
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(int c) {
                        count.set(c);
                        latch.countDown();
                    }
                    @Override
                    public void onSendFailed(String err) {
                        error.set(err);
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        // Either connect fails OR it sends — either way the message was not filtered
        // The key assertion is it didn't call onSendComplete(0) due to filtering
        assertTrue("ZONE message should NOT be filtered (should attempt send)",
                error.get() != null || count.get() > 0);
    }
}
