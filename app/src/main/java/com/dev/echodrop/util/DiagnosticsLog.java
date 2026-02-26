package com.dev.echodrop.util;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * In-memory ring buffer logger for on-device diagnostics.
 *
 * <p>Captures the last {@link #MAX_ENTRIES} log entries with timestamps.
 * Entries tagged with "ED:" prefixes (the structured logging convention)
 * are captured automatically via the installed {@link DiagTree}.</p>
 *
 * <p>Usage from UI: {@code DiagnosticsLog.getEntries()} to read,
 * {@code DiagnosticsLog.getAllText()} to copy to clipboard.</p>
 */
public final class DiagnosticsLog {

    /** Maximum number of entries retained in the ring buffer. */
    private static final int MAX_ENTRIES = 500;

    private static final LinkedList<String> entries = new LinkedList<>();
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DiagnosticsLog() { /* Utility class */ }

    /**
     * Appends a log entry with the current timestamp.
     * Thread-safe.
     */
    public static synchronized void log(@NonNull String tag, @NonNull String message) {
        final String entry = TIME_FMT.format(new Date()) + " [" + tag + "] " + message;
        entries.addLast(entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /** Returns a snapshot of all current entries (oldest first). */
    @NonNull
    public static synchronized List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    /** Returns all entries as a single newline-separated string. */
    @NonNull
    public static synchronized String getAllText() {
        final StringBuilder sb = new StringBuilder();
        for (final String entry : entries) {
            sb.append(entry).append('\n');
        }
        return sb.toString();
    }

    /** Clears all entries. */
    public static synchronized void clear() {
        entries.clear();
    }

    /** Returns the number of current entries. */
    public static synchronized int size() {
        return entries.size();
    }

    // ──────────────────── Timber Integration ────────────────────

    /**
     * Custom Timber Tree that captures ED:-prefixed logs into the
     * ring buffer. Install via {@code Timber.plant(new DiagTree())}.
     */
    public static class DiagTree extends Timber.Tree {
        @Override
        protected void log(int priority, String tag, @NonNull String message, Throwable t) {
            // Only capture ED:-prefixed messages (our structured logs)
            if (tag != null && tag.startsWith("ED:")) {
                DiagnosticsLog.log(tag, message);
            } else if (message.startsWith("ED:")) {
                DiagnosticsLog.log(tag != null ? tag : "?", message);
            }
        }
    }
}
