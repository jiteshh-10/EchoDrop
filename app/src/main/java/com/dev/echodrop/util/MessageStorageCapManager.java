package com.dev.echodrop.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.dev.echodrop.db.AppDatabase;
import com.dev.echodrop.db.MessageDao;

import timber.log.Timber;

/**
 * Enforces the user-configurable cached bundle storage cap.
 *
 * <p>Eviction priority remains: BULK first, then NORMAL; ALERT is preserved.</p>
 */
public final class MessageStorageCapManager {

    private static final String TAG = "ED:StorageCap";
    private static final int MAX_EVICTION_ITERATIONS = 4000;

    private MessageStorageCapManager() {
        // No instances.
    }

    public static void enforceNow(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final MessageDao dao = AppDatabase.getInstance(appContext).messageDao();
        enforce(dao, appContext);
    }

    public static void enforce(@NonNull MessageDao dao, @NonNull Context context) {
        final long capBytes = AppPreferences.getStorageCapBytes(context);
        long estimateBytes = dao.estimateStorageBytes();

        if (estimateBytes <= capBytes) {
            return;
        }

        int deletedRows = 0;
        int loops = 0;

        while (estimateBytes > capBytes && loops < MAX_EVICTION_ITERATIONS) {
            loops++;
            int deleted = dao.deleteOldestBulk(1);
            if (deleted <= 0) {
                deleted = dao.deleteOldestNormal(1);
            }
            if (deleted <= 0) {
                break;
            }
            deletedRows += deleted;
            estimateBytes = dao.estimateStorageBytes();
        }

        Timber.tag(TAG).i(
                "ED:STORAGE_CAP_ENFORCE cap_bytes=%d estimated_bytes=%d deleted_rows=%d loops=%d",
                capBytes,
                estimateBytes,
                deletedRows,
                loops);
    }
}
