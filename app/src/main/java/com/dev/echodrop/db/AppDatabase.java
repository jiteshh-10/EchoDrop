package com.dev.echodrop.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database for EchoDrop local message persistence.
 *
 * <p>Singleton pattern ensures a single database instance per application lifecycle.
 * Uses destructive migration for now (pre-release); will add proper migrations
 * when the schema stabilizes.</p>
 */
@Database(
        entities = {MessageEntity.class, ChatEntity.class, ChatMessageEntity.class},
        version = 5,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "echodrop_db";

    private static volatile AppDatabase INSTANCE;

    public abstract MessageDao messageDao();

    public abstract ChatDao chatDao();

    /**
     * Returns the singleton database instance.
     * Thread-safe via double-checked locking.
     *
     * @param context Application context (avoids activity leaks).
     * @return The singleton AppDatabase instance.
     */
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DATABASE_NAME)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Allows injecting a custom instance for testing.
     * Only call from test code.
     */
    public static void setInstance(AppDatabase testInstance) {
        INSTANCE = testInstance;
    }

    /**
     * Clears the singleton. Used in tests to reset state.
     */
    public static void destroyInstance() {
        INSTANCE = null;
    }
}
