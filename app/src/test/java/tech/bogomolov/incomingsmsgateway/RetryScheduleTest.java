package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-JVM tests for {@link RetrySchedule}: the 5/10/20/30/30… ladder and the
 * 100 s cutoff after which a one-time code is never (re)sent.
 */
public class RetryScheduleTest {

    @Test
    public void ladderIs5Then10Then20Then30Repeating() {
        assertEquals(5_000L, RetrySchedule.delayMillis(0));
        assertEquals(10_000L, RetrySchedule.delayMillis(1));
        assertEquals(20_000L, RetrySchedule.delayMillis(2));
        assertEquals(30_000L, RetrySchedule.delayMillis(3));
        assertEquals(30_000L, RetrySchedule.delayMillis(4));
        assertEquals(30_000L, RetrySchedule.delayMillis(50));
    }

    @Test
    public void negativeIndexClampsToFirstStep() {
        assertEquals(5_000L, RetrySchedule.delayMillis(-1));
    }

    @Test
    public void deadlineIs100SecondsAfterReceipt() {
        assertEquals(100_000L, RetrySchedule.DEADLINE_MS);
        assertEquals(1_700_000_100_000L, RetrySchedule.deadlineFor(1_700_000_000_000L));
    }

    @Test
    public void walkingTheLadderStopsBeforeTheDeadline() {
        // First fallback attempt at t=1 s fails; every later attempt must land at or
        // before t=100 s, and the ladder must stop by itself.
        long deadline = RetrySchedule.deadlineFor(0L);
        long now = 1_000L;
        List<Long> attemptTimes = new ArrayList<>();
        for (int retry = 0; ; retry++) {
            long delay = RetrySchedule.nextDelayMillis(retry, now, deadline);
            if (delay < 0L) {
                break;
            }
            now += delay;
            attemptTimes.add(now);
        }
        assertEquals(Arrays.asList(6_000L, 16_000L, 36_000L, 66_000L, 96_000L), attemptTimes);
        assertTrue(attemptTimes.get(attemptTimes.size() - 1) <= deadline);
    }

    @Test
    public void noRetryIsScheduledPastTheDeadline() {
        assertEquals(-1L, RetrySchedule.nextDelayMillis(0, 96_000L, 100_000L));
        // Landing exactly on the deadline is still allowed.
        assertEquals(5_000L, RetrySchedule.nextDelayMillis(0, 95_000L, 100_000L));
    }

    @Test
    public void expiredOnlyStrictlyAfterDeadline() {
        assertFalse(RetrySchedule.isExpired(100_000L, 100_000L));
        assertTrue(RetrySchedule.isExpired(100_001L, 100_000L));
    }

    @Test
    public void storedCodeIsStaleOnlyAfterItsWindow() {
        long now = 1_700_000_000_000L;
        assertFalse(RetrySchedule.isPastDeadline(now - 50_000L, now));
        assertFalse(RetrySchedule.isPastDeadline(now - 100_000L, now));
        assertTrue(RetrySchedule.isPastDeadline(now - 100_001L, now));
        // Unknown receive time (legacy stored entry): keep it.
        assertFalse(RetrySchedule.isPastDeadline(0L, now));
    }
}
