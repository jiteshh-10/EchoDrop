package com.dev.echodrop.util;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.dev.echodrop.MainActivity;
import com.dev.echodrop.R;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility for app message notifications (incoming/outgoing).
 */
public final class MessageNotificationHelper {

    public static final String CHANNEL_ID = "new_messages";
    private static final int NOTIFICATION_ID_BASE = 5000;
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

    private MessageNotificationHelper() {
        // No instances.
    }

    public static boolean canPostNotifications(@NonNull Context context) {
        if (!AppPreferences.isMessageAlertsEnabled(context)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    public static void notifyOutgoingBroadcast(@NonNull Context context,
                                               @NonNull String messageText) {
        final Context appContext = context.getApplicationContext();
        if (!canPostNotifications(appContext)) {
            return;
        }

        createChannelIfNeeded(appContext);

        final Intent tapIntent = new Intent(appContext, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        final PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                nextRequestCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final String compactText = trimForNotification(messageText);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.notification_broadcast_sent_title))
                .setContentText(compactText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(compactText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE);

        NotificationManagerCompat.from(appContext)
                .notify(nextNotificationId(), builder.build());
    }

    public static void notifyOutgoingChat(@NonNull Context context,
                                          @NonNull String chatId,
                                          @NonNull String chatCode,
                                          @NonNull String chatName,
                                          @NonNull String messageText) {
        final Context appContext = context.getApplicationContext();
        if (!canPostNotifications(appContext)) {
            return;
        }

        createChannelIfNeeded(appContext);

        final Intent tapIntent = new Intent(appContext, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("navigate_to", "chat_conversation")
                .putExtra("chat_id", chatId)
                .putExtra("chat_code", chatCode)
                .putExtra("chat_name", chatName);

        final PendingIntent pendingIntent = PendingIntent.getActivity(
                appContext,
                nextRequestCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final String title = TextUtils.isEmpty(chatName)
                ? appContext.getString(R.string.notification_room_message_sent_title)
                : chatName;
        final String compactText = trimForNotification(messageText);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(compactText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(compactText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE);

        NotificationManagerCompat.from(appContext)
                .notify(nextNotificationId(), builder.build());
    }

    private static String trimForNotification(@NonNull String text) {
        final String value = text.trim();
        if (value.length() <= 120) {
            return value;
        }
        return value.substring(0, 120) + "...";
    }

    private static void createChannelIfNeeded(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        final NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.transfer_notification_title));
        manager.createNotificationChannel(channel);
    }

    private static int nextRequestCode() {
        final int counter = ID_COUNTER.incrementAndGet();
        return (int) ((System.currentTimeMillis() % 10_000) + counter);
    }

    private static int nextNotificationId() {
        return NOTIFICATION_ID_BASE + ID_COUNTER.incrementAndGet();
    }
}
