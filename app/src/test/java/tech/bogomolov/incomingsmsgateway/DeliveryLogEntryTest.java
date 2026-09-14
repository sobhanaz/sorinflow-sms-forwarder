package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-JVM tests for building and serialising a delivery-log entry. */
public class DeliveryLogEntryTest {

    @Test
    public void buildsAnEntryFromThePayloadAndRequest() {
        String payload = "{\"kind\":\"contact\",\"code\":\"523969\",\"sim\":\"sim1\",\"text\":\"x\"}";
        Request request = new Request("https://sorinflow.example/x", payload); // never executed
        long received = System.currentTimeMillis() - 1_200L;

        DeliveryLog.Entry entry = DeliveryLog.build(payload, received, request, Request.RESULT_RETRY);

        assertEquals("contact", entry.kind);
        assertEquals("••••69", entry.code);
        assertEquals("sim1", entry.sim);
        assertEquals(-1, entry.http);
        assertEquals(DeliveryStatus.RESULT_RETRYING, entry.result);
        assertTrue(entry.sinceReceivedMs >= 1_200L);
    }

    @Test
    public void codeFallsBackToTheTextWhenTheRegexMissed() {
        String payload = "{\"kind\":\"login\",\"code\":\"\",\"text\":\"کد تایید دیوار: ۵۹۳۱۵۴\"}";
        Request request = new Request("https://sorinflow.example/x", payload);

        DeliveryLog.Entry entry = DeliveryLog.build(payload, 0L, request, Request.RESULT_SUCCESS);

        assertEquals("••••54", entry.code);
        assertEquals(-1L, entry.sinceReceivedMs);
        assertEquals(DeliveryStatus.RESULT_OK, entry.result);
    }

    @Test
    public void jsonRoundTripKeepsEveryField() throws Exception {
        DeliveryLog.Entry entry = new DeliveryLog.Entry(1_700_000_000_000L, "test", "", "", 200, 412L,
                1_280L, DeliveryStatus.RESULT_OK, "test");

        DeliveryLog.Entry back = DeliveryLog.Entry.fromJson(entry.toJson());

        assertEquals(entry.time, back.time);
        assertEquals(entry.kind, back.kind);
        assertEquals(entry.http, back.http);
        assertEquals(entry.rttMs, back.rttMs);
        assertEquals(entry.sinceReceivedMs, back.sinceReceivedMs);
        assertEquals(entry.result, back.result);
        assertEquals(entry.reason, back.reason);
        assertEquals(DeliveryStatus.RESULT_FAILED, back.withResult(DeliveryStatus.RESULT_FAILED).result);
    }
}
