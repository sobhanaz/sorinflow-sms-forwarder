package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Instrumented tests for {@link SorinFlowRules#apply}: the rules land in the rule
 * store under fixed keys (so re-running setup updates rather than duplicates), the
 * heartbeat is switched on, and no secret is written to the plain-text store.
 */
@RunWith(AndroidJUnit4.class)
public class SorinFlowRulesApplyTest {

    private static final String HEARTBEAT_PREFERENCE = "heartbeat";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        clearAll();
    }

    @After
    public void tearDown() {
        clearAll();
    }

    @Test
    public void testApplyInstallsTwoRulesAndTheHeartbeat() {
        SorinFlowRules.apply(context, settings("https://sorinflow.example"));

        ArrayList<ForwardingConfig> rules = ForwardingConfig.getAll(context);
        assertEquals(2, rules.size());
        for (ForwardingConfig rule : rules) {
            assertTrue(SorinFlowRules.isSorinFlowRule(rule));
            assertEquals("https://sorinflow.example/api/scraper/otp-inbound", rule.getUrl());
        }

        HeartbeatSettings heartbeat = HeartbeatSettings.load(context);
        assertTrue(heartbeat.isEnabled());
        assertEquals("https://sorinflow.example/api/scraper/forwarder-heartbeat", heartbeat.getUrl());
        assertEquals(HeartbeatSettings.DEFAULT_INTERVAL_MINUTES, heartbeat.getIntervalMinutes());
    }

    @Test
    public void testApplyTwiceUpdatesInsteadOfDuplicating() {
        SorinFlowRules.apply(context, settings("https://old.sorinflow.example"));
        SorinFlowRules.apply(context, settings("https://sorinflow.example"));

        ArrayList<ForwardingConfig> rules = ForwardingConfig.getAll(context);
        assertEquals(2, rules.size());
        for (ForwardingConfig rule : rules) {
            assertEquals("https://sorinflow.example/api/scraper/otp-inbound", rule.getUrl());
        }
    }

    @Test
    public void testStoredRulesCarryNoSecretButSignWithTheSetupOne() throws Exception {
        SorinFlowSettings settings = settings("https://sorinflow.example");
        settings.save(context);
        SorinFlowRules.apply(context, settings);

        assertFalse(ForwardingConfig.exportToJson(context).contains("s3cret"));
        for (ForwardingConfig rule : ForwardingConfig.getAll(context)) {
            assertNull(rule.getSignHmacSha256Secret());
            assertEquals("s3cret", SorinFlowRules.resolveSecret(context, rule));
        }
    }

    private SorinFlowSettings settings(String baseUrl) {
        return new SorinFlowSettings(baseUrl, "09123456789", "s3cret");
    }

    private void clearAll() {
        context.getSharedPreferences(context.getString(R.string.key_phones_preference),
                Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(HEARTBEAT_PREFERENCE, Context.MODE_PRIVATE)
                .edit().clear().commit();
        SorinFlowSettings.clear(context);
    }
}
