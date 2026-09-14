package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.TestDriver;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * End to end on a device/emulator: a realistic Divar SMS (PDU with alphanumeric
 * sender and Persian text) is handed to the manifest receiver exactly as Android
 * would, and a local mock server plays SorinFlow. Checks the path, the HMAC
 * signature, the JSON contract, the in-app latency from injection to the server's
 * doorstep, the status/log records, and the WorkManager fallback after a 500.
 */
@RunWith(AndroidJUnit4.class)
public class EndToEndDeliveryTest {

    private static final String SECRET = "e2e-secret";
    private static final String ACCOUNT = "09123456789";
    private static final String CONTACT_SMS = "کد امنیتی دریافت اطلاعات تماس دیوار:\nCode: 523969";
    private static final String SERVER_OK =
            "{\"matched\":true,\"kind\":\"contact\",\"reason\":\"ok\",\"latency_ms\":1}";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private MockWebServer server;

    @Before
    public void setup() throws Exception {
        Configuration config = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(new SynchronousExecutor())
                .build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config);
        clearState();

        server = new MockWebServer();
        server.start();
        // MockWebServer listens on the loopback address, which the network security
        // config allows in clear text; apply() does not re-validate the scheme.
        String baseUrl = server.url("/").toString().replaceAll("/+$", "");
        SorinFlowSettings settings = new SorinFlowSettings(baseUrl, ACCOUNT, SECRET);
        settings.save(context);
        SorinFlowRules.apply(context, settings);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        clearState();
    }

    @Test
    public void pduDecodesAsDivarMessage() {
        SmsMessage message = SmsMessage.createFromPdu(SmsPdu.deliver("Divar", CONTACT_SMS), "3gpp");
        assertEquals("Divar", message.getOriginatingAddress());
        assertEquals(CONTACT_SMS, message.getMessageBody());
    }

    @Test
    public void codeReachesTheServerSignedAndFast() throws Exception {
        server.enqueue(new MockResponse().setBody(SERVER_OK));
        long injected = System.currentTimeMillis();

        new SmsBroadcastReceiver().onReceive(context, smsIntent("Divar", CONTACT_SMS));

        RecordedRequest request = server.takeRequest(15, TimeUnit.SECONDS);
        long latency = System.currentTimeMillis() - injected;
        assertNotNull("no request reached the server", request);
        // Picked up by CI from logcat (the app's own files are gone after the uninstall).
        Log.i("EndToEnd", "SMS injected -> request at server: " + latency + " ms");

        assertEquals("POST", request.getMethod());
        assertEquals("/api/scraper/otp-inbound", request.getPath());
        String body = request.getBody().readUtf8();
        assertEquals(Request.computeHmacSha256Hex(SECRET, body), request.getHeader("X-Signature"));
        JSONObject json = new JSONObject(body);
        assertEquals("contact", json.getString("kind"));
        assertEquals(ACCOUNT, json.getString("account"));
        assertEquals("523969", json.getString("code"));
        assertTrue(json.getString("text").contains("Code: 523969"));
        assertTrue(json.getLong("sentStamp") > 0);
        assertTrue(json.getLong("receivedStamp") >= injected);
        assertTrue("in-app latency was " + latency + " ms", latency < 5_000L);

        awaitResult(DeliveryStatus.RESULT_OK);
        assertTrue(DeliveryStatus.prefs(context).getLong(DeliveryStatus.KEY_MSG_SINCE_RECEIVED, -1L) >= 0);
        List<DeliveryLog.Entry> log = DeliveryLog.getAll(context);
        assertEquals(1, log.size());
        assertEquals("contact", log.get(0).kind);
        assertEquals("••••69", log.get(0).code);
        assertEquals(DeliveryStatus.RESULT_OK, log.get(0).result);
        assertEquals(200, log.get(0).http);
    }

    @Test
    public void serverErrorFallsBackToWorkManagerAndSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"detail\":\"boom\"}"));
        server.enqueue(new MockResponse().setBody(SERVER_OK));

        new SmsBroadcastReceiver().onReceive(context, smsIntent("Divar", CONTACT_SMS));

        RecordedRequest first = server.takeRequest(15, TimeUnit.SECONDS);
        assertNotNull(first);
        // The direct attempt failed; the test WorkManager holds the fallback until
        // its network constraint is declared met.
        releaseFallback();
        RecordedRequest second = server.takeRequest(15, TimeUnit.SECONDS);
        assertNotNull("fallback request never arrived", second);
        assertEquals("/api/scraper/otp-inbound", second.getPath());

        awaitResult(DeliveryStatus.RESULT_OK);
        List<DeliveryLog.Entry> log = DeliveryLog.getAll(context);
        assertEquals(2, log.size());
        assertEquals(DeliveryStatus.RESULT_OK, log.get(0).result);
        assertEquals(DeliveryStatus.RESULT_RETRYING, log.get(1).result);
        assertEquals(500, log.get(1).http);
        assertEquals("boom", log.get(1).reason);
    }

    private void releaseFallback() throws Exception {
        TestDriver driver = WorkManagerTestInitHelper.getTestDriver(context);
        assertNotNull(driver);
        for (int i = 0; i < 100; i++) {
            List<WorkInfo> infos = WorkManager.getInstance(context)
                    .getWorkInfosByTag(RequestWorker.TAG_DELIVERY).get();
            for (WorkInfo info : infos) {
                if (info.getState() == WorkInfo.State.ENQUEUED) {
                    driver.setAllConstraintsMet(info.getId());
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no fallback work was enqueued");
    }

    private void awaitResult(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(DeliveryStatus.prefs(context).getString(DeliveryStatus.KEY_MSG_RESULT, ""))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("delivery status never became " + expected);
    }

    private Intent smsIntent(String sender, String body) {
        Intent intent = new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        intent.putExtra("pdus", new byte[][]{SmsPdu.deliver(sender, body)});
        intent.putExtra("format", "3gpp");
        return intent;
    }

    private void clearState() {
        context.getSharedPreferences(context.getString(R.string.key_phones_preference),
                Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("heartbeat", Context.MODE_PRIVATE).edit().clear().commit();
        DeliveryStatus.prefs(context).edit().clear().commit();
        DeliveryLog.clear(context);
        FailedMessage.clear(context);
        SorinFlowSettings.clear(context);
    }
}
