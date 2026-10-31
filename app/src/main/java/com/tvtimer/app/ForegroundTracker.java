package com.tvtimer.app;

/**
 * Shared holder for the most recent foreground-package signal.
 * Written by TvAccessibilityService (window-state events) and by the
 * UsageStats cross-check inside WatchdogService; read by TimerEngine.
 */
public final class ForegroundTracker {

    /** Current foreground package, or null when unknown (e.g. screen off). */
    public static volatile String pkg;

    /** SystemClock.elapsedRealtime() at the moment the signal was produced. */
    public static volatile long atElapsed;

    /** Wall-clock timestamp (ms) of the underlying usage-stats event, if any. */
    public static volatile long usageEventWallMs;

    private ForegroundTracker() {
    }

    public static void update(String packageName, long elapsedNow) {
        pkg = packageName;
        atElapsed = elapsedNow;
    }
}
