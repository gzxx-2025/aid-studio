package com.aid.diagnostics;

import java.util.concurrent.TimeUnit;

/** Distinguishes local diagnostic retention from short-lived verification authorization. */
final class DiagnosticRetention {
    static final int LOCAL_DAYS = 7;
    static final long LOCAL_MILLIS = TimeUnit.DAYS.toMillis(LOCAL_DAYS);
    static final long VERIFICATION_USE_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private DiagnosticRetention() { }

    static boolean expired(long createdAt, long now) {
        return createdAt <= now - LOCAL_MILLIS;
    }
}
