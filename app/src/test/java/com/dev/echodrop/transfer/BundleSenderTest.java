package com.dev.echodrop.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dev.echodrop.db.MessageEntity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for BundleSender — verifies send behavior and edge cases.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class BundleSenderTest {

    private MessageEntity createMessage(String id, String text, String priority,
                                        long createdAt, long expiresAt) {
        final String hash = MessageEntity.computeHash(text, "LOCAL", createdAt);
        return new MessageEntity(id, text, "LOCAL", priority, createdAt, expiresAt, false, hash);
    }

    @Test
    public void sender_canBeCreated() {
        final BundleSender sender = new BundleSender();
        assertNotNull(sender);
        sender.shutdown();
    }

    @Test
    public void sender_filtersExpiredMessages() throws InterruptedException {
        // Expired message (expiresAt in the past)
        final long past = System.currentTimeMillis() - 10_000;
        final MessageEntity expiredMsg = createMessage("expired", "Old", "NORMAL",
                past - 3_600_000, past);

        // We can't connect to a real server, but we can verify filtering by
        // using an unreachable address and checking that the callback reflects
        // the empty-after-filter case.

        // Since all messages are expired, the sender should call onSendComplete(0)
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger sentCount = new AtomicInteger(-1);

        final BundleSender sender = new BundleSender();
        sender.send(InetAddress.getLoopbackAddress(),
                Collections.singletonList(expiredMsg),
                new BundleSender.SendCallback() {
                    @Override
                    public void onSendComplete(final int count) {
                        sentCount.set(count);
                        latch.countDown();
                    }

                    @Override
                    public void onSendFailed(final String error) {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, sentCount.get());
        sender.shutdown();
    }

    @Test
    public void sender_failsOnUnreachableAddress() throws InterruptedException {
        final long future = System.currentTimeMillis() + 3_600_000;
        final MessageEntity msg = createMessage("msg1", "Hello", "NORMAL",
                System.currentTimeMillis(), future);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        final BundleSender sender = new BundleSender();
        // Use a non-routable address to force connection failure
        try {
            final InetAddress unreachable = InetAddress.getByName("192.0.2.1");
            sender.send(unreachable, Collections.singletonList(msg),
                    new BundleSender.SendCallback() {
                        @Override
                        public void onSendComplete(final int count) {
                            latch.countDown();
                        }

                        @Override
                        public void onSendFailed(final String err) {
                            error.set(err);
                            latch.countDown();
                        }
                    });

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertNotNull(error.get());
        } catch (Exception e) {
            // Expected on some test configurations
        }
        sender.shutdown();
    }

    @Test
    public void sender_shutdownCleansUp() {
        final BundleSender sender = new BundleSender();
        sender.shutdown(); // Should not throw
    }
}
