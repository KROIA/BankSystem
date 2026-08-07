package net.kroia.banksystem.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Spec A.4 / A.7 (v2.0.8) — human-readable durations from game ticks and
 * timestamps from epoch millis.
 */
public final class TimeFormat {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormat() {}

    /**
     * Format a tick count as a compact duration, e.g. {@code "12m 43s"},
     * {@code "1d 2h"}, {@code "5s"}. Negative or zero ticks render as {@code "0s"}.
     * 20 ticks = 1 second.
     */
    public static String formatTickDuration(long ticks) {
        long totalSeconds = Math.max(0L, ticks / 20L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0) return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        if (hours > 0) return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        if (minutes > 0) return minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
        return seconds + "s";
    }

    /** Format epoch millis as {@code yyyy-MM-dd HH:mm:ss} in the local time zone. */
    public static String formatTimestamp(long epochMillis) {
        try {
            return TIMESTAMP_FORMAT.format(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
        } catch (Exception e) {
            return String.valueOf(epochMillis);
        }
    }
}
