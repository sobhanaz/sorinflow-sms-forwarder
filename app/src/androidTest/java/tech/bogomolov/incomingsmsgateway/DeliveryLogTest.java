package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Instrumented tests for {@link DeliveryLog}'s storage: order, cap, failure marking. */
@RunWith(AndroidJUnit4.class)
public class DeliveryLogTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        DeliveryLog.clear(context);
    }

    @After
    public void tearDown() {
        DeliveryLog.clear(context);
    }

    @Test
    public void testNewestFirstAndCappedAtTwenty() {
        for (int i = 0; i < DeliveryLog.MAX_ENTRIES + 5; i++) {
            DeliveryLog.append(context, entry(i));
        }

        List<DeliveryLog.Entry> all = DeliveryLog.getAll(context);
        assertEquals(DeliveryLog.MAX_ENTRIES, all.size());
        assertEquals(DeliveryLog.MAX_ENTRIES + 4, all.get(0).time);
        assertEquals(5, all.get(all.size() - 1).time);
    }

    @Test
    public void testMarkNewestFailed() {
        DeliveryLog.append(context, entry(1));
        DeliveryLog.append(context, entry(2));

        DeliveryLog.markNewestFailed(context);

        List<DeliveryLog.Entry> all = DeliveryLog.getAll(context);
        assertEquals(DeliveryStatus.RESULT_FAILED, all.get(0).result);
        assertEquals(DeliveryStatus.RESULT_RETRYING, all.get(1).result);
    }

    @Test
    public void testClear() {
        DeliveryLog.append(context, entry(1));
        DeliveryLog.clear(context);
        assertEquals(0, DeliveryLog.getAll(context).size());
    }

    private DeliveryLog.Entry entry(long time) {
        return new DeliveryLog.Entry(time, "contact", "••••69", "sim1", 503, 300L, 1_000L,
                DeliveryStatus.RESULT_RETRYING, "http 503");
    }
}
