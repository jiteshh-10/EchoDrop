package com.dev.echodrop.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.dev.echodrop.repository.MessageRepo;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager periodic worker that cleans up expired messages from the Room database.
 *
 * <p>Runs every 15 minutes to delete messages whose TTL has expired.
 * Also triggered manually on app foreground (MainActivity.onResume).</p>
 *
 * <p>Eviction is handled by {@link MessageRepo#cleanupExpiredSync()},
 * which deletes all rows where expires_at &lt; now.</p>
 */
public class TtlCleanupWorker extends Worker {

    private static final String TAG = "TtlCleanupWorker";
    private static final String UNIQUE_WORK_NAME = "ttl_cleanup";
    private static final long REPEAT_INTERVAL_MINUTES = 15;

    public TtlCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            MessageRepo repo = new MessageRepo(getApplicationContext());
            int deleted = repo.cleanupExpiredSync();
            Log.d(TAG, "TTL cleanup completed. Deleted " + deleted + " expired messages.");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "TTL cleanup failed", e);
            return Result.retry();
        }
    }

    /**
     * Schedules the periodic TTL cleanup work.
     * Uses KEEP policy so it doesn't cancel existing scheduled work.
     *
     * @param context Application context.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                TtlCleanupWorker.class,
                REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().build())
                .build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        UNIQUE_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request);

        Log.d(TAG, "Scheduled periodic TTL cleanup every " + REPEAT_INTERVAL_MINUTES + " minutes");
    }

    /**
     * Run a one-time immediate cleanup (called from MainActivity.onResume).
     *
     * @param context Application context.
     */
    public static void runOnce(Context context) {
        androidx.work.OneTimeWorkRequest request =
                new androidx.work.OneTimeWorkRequest.Builder(TtlCleanupWorker.class)
                        .build();
        WorkManager.getInstance(context).enqueue(request);
        Log.d(TAG, "Enqueued one-time TTL cleanup");
    }
}
