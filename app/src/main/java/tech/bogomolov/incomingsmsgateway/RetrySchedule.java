package tech.bogomolov.incomingsmsgateway;

/**
 * Deadline-bound retry ladder for time-critical deliveries (Divar one-time codes,
 * which the site accepts for roughly two minutes). Waits 5 s, 10 s, 20 s, then 30 s
 * between attempts and never schedules one past {@link #DEADLINE_MS} after the SMS
 * was received: a code that old is useless, so sending it would only waste a
 * request. Pure functions, so the schedule is unit-tested on the JVM.
 */
public final class RetrySchedule {

    /** Wait before retry #0, #1, #2, ...; the last value repeats. */
    static final long[] DELAYS_MS = {5_000L, 10_000L, 20_000L, 30_000L};

    /** No attempt starts later than this after the SMS was received. */
    public static final long DEADLINE_MS = 100_000L;

    private RetrySchedule() {
    }

    public static long deadlineFor(long receivedStamp) {
        return receivedStamp + DEADLINE_MS;
    }

    /** Delay before the given retry (0-based); clamps to the last ladder step. */
    public static long delayMillis(int retryIndex) {
        int index = Math.max(0, Math.min(retryIndex, DELAYS_MS.length - 1));
        return DELAYS_MS[index];
    }

    /**
     * Delay before retry #retryIndex, or -1 when that retry would start after the
     * deadline (in which case the caller gives up and stores the message).
     */
    public static long nextDelayMillis(int retryIndex, long now, long deadline) {
        long delay = delayMillis(retryIndex);
        return now + delay > deadline ? -1L : delay;
    }

    public static boolean isExpired(long now, long deadline) {
        return now > deadline;
    }
}
