package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * Pure-JVM tests for {@link SorinFlowRules}: the two generated rules, the exact
 * request body contract of the SorinFlow server, HMAC/https settings, and base URL
 * validation. A null Context is enough: nothing here touches SharedPreferences,
 * and the device-health placeholders fall back to -1 / "none".
 */
public class SorinFlowRulesTest {

    private static final String CONTACT_SMS = "کد امنیتی دریافت اطلاعات تماس دیوار:\nCode: 523969";
    private static final String LOGIN_SMS = "کد تایید دیوار:\nCode: 593154\nبرای دیگران نفرستید.";
    private static final String ASTERISK = "*";

    private static final SorinFlowSettings SETTINGS =
            new SorinFlowSettings("https://sorinflow.com", "09123456789", "s3cret");

    private List<ForwardingConfig> rules() {
        return SorinFlowRules.buildRules(null, SETTINGS);
    }

    @Test
    public void buildsContactAndLoginRulesWithFixedKeys() {
        List<ForwardingConfig> rules = rules();
        assertEquals(2, rules.size());

        ForwardingConfig contact = rules.get(0);
        assertEquals(SorinFlowRules.KEY_CONTACT, contact.getKey());
        assertEquals("Divar", contact.getSender());
        assertFalse(contact.getIsSenderRegex());
        assertEquals("اطلاعات تماس", contact.getSmsFilter());

        ForwardingConfig login = rules.get(1);
        assertEquals(SorinFlowRules.KEY_LOGIN, login.getKey());
        assertEquals("Divar", login.getSender());
        assertEquals("کد تایید", login.getSmsFilter());

        for (ForwardingConfig rule : rules) {
            assertEquals("https://sorinflow.com/api/scraper/otp-inbound", rule.getUrl());
            assertTrue(rule.getSignHmacSha256());
            // The secret is resolved from the encrypted settings at send time.
            assertNull(rule.getSignHmacSha256Secret());
            assertFalse(rule.getIgnoreSsl());
            assertFalse(rule.getChunkedMode());
            assertTrue(rule.getStoreFailed());
            assertTrue(rule.getIsSmsEnabled());
            assertFalse(rule.getLocalMode());
            assertEquals(0, rule.getSimSlot());
            assertTrue(SorinFlowRules.isSorinFlowRule(rule));
        }
    }

    @Test
    public void contactTemplateRendersTheServerContract() throws Exception {
        ForwardingConfig contact = rules().get(0);
        long sentStamp = 1_700_000_000_000L;

        String body = contact.prepareMessage("Divar", CONTACT_SMS, "sim1", sentStamp);
        JSONObject json = new JSONObject(body);

        assertEquals("contact", json.getString("kind"));
        assertEquals("09123456789", json.getString("account"));
        assertEquals("523969", json.getString("code"));
        assertEquals(CONTACT_SMS, json.getString("text"));
        assertEquals("sim1", json.getString("sim"));
        assertEquals(sentStamp, json.getLong("sentStamp"));
        assertTrue(json.getLong("receivedStamp") >= sentStamp);
        assertEquals(-1, json.getInt("battery"));
        assertEquals("none", json.getString("network"));
        assertEquals(9, json.length());
    }

    @Test
    public void loginTemplateHasLoginKind() throws Exception {
        ForwardingConfig login = rules().get(1);

        JSONObject json = new JSONObject(login.prepareMessage("Divar", LOGIN_SMS, "sim1", 1L));

        assertEquals("login", json.getString("kind"));
        assertEquals("593154", json.getString("code"));
    }

    @Test
    public void codeIsEmptyWhenTheSmsHasNoLatinCodeButTextIsKept() throws Exception {
        // The server normalises Persian digits itself and extracts from text when
        // "code" is unusable, so the text must always be sent verbatim.
        String persian = "کد تایید دیوار: ۵۹۳۱۵۴";
        ForwardingConfig login = rules().get(1);

        JSONObject json = new JSONObject(login.prepareMessage("Divar", persian, "sim1", 1L));

        assertEquals("", json.getString("code"));
        assertEquals(persian, json.getString("text"));
    }

    @Test
    public void heartbeatTemplateRendersTheServerContract() throws Exception {
        ForwardingConfig template = new ForwardingConfig(null);
        template.setTemplate(SorinFlowRules.heartbeatTemplate("09123456789"));

        JSONObject json = new JSONObject(template.prepareMessage("", "", "", 0L));

        assertEquals("09123456789", json.getString("account"));
        assertEquals(-1, json.getInt("battery"));
        assertEquals("none", json.getString("network"));
        assertEquals(BuildConfig.VERSION_NAME, json.getString("version"));
        assertEquals(4, json.length());
    }

    @Test
    public void filtersSelectTheRightDivarMessage() {
        assertTrue(SmsBroadcastReceiver.matchesFilter(SorinFlowRules.FILTER_CONTACT, CONTACT_SMS));
        assertFalse(SmsBroadcastReceiver.matchesFilter(SorinFlowRules.FILTER_LOGIN, CONTACT_SMS));
        assertTrue(SmsBroadcastReceiver.matchesFilter(SorinFlowRules.FILTER_LOGIN, LOGIN_SMS));
        assertFalse(SmsBroadcastReceiver.matchesFilter(SorinFlowRules.FILTER_CONTACT, LOGIN_SMS));
    }

    @Test
    public void senderMatchesDivarExactly() {
        ForwardingConfig contact = rules().get(0);
        assertTrue(SmsBroadcastReceiver.matchesSender(contact, "Divar", ASTERISK));
        assertFalse(SmsBroadcastReceiver.matchesSender(contact, "DIVAR", ASTERISK));
        assertFalse(SmsBroadcastReceiver.matchesSender(contact, "+989000000000", ASTERISK));
    }

    @Test
    public void isSorinFlowRuleGoesByKeyPrefix() {
        ForwardingConfig custom = new ForwardingConfig(null);
        assertFalse(SorinFlowRules.isSorinFlowRule(custom));
        custom.setKey("1700000000000_123456");
        assertFalse(SorinFlowRules.isSorinFlowRule(custom));
        custom.setKey("sorinflow_contact");
        assertTrue(SorinFlowRules.isSorinFlowRule(custom));
    }

    @Test
    public void normalizeBaseUrlAcceptsHttpsAndStripsSlashesAndPastedEndpoints() {
        assertEquals("https://sorinflow.com", SorinFlowRules.normalizeBaseUrl("https://sorinflow.com"));
        assertEquals("https://sorinflow.com", SorinFlowRules.normalizeBaseUrl(" https://sorinflow.com/ "));
        assertEquals("https://sorinflow.com",
                SorinFlowRules.normalizeBaseUrl("https://sorinflow.com/api/scraper/otp-inbound"));
        assertEquals("https://sorinflow.com",
                SorinFlowRules.normalizeBaseUrl("https://sorinflow.com/api/scraper/forwarder-heartbeat/"));
        assertEquals("https://staging.sorinflow.com:8443",
                SorinFlowRules.normalizeBaseUrl("https://staging.sorinflow.com:8443/"));
    }

    @Test
    public void normalizeBaseUrlRejectsAnythingButHttps() {
        assertNull(SorinFlowRules.normalizeBaseUrl("http://sorinflow.com"));
        assertNull(SorinFlowRules.normalizeBaseUrl("sorinflow.com"));
        assertNull(SorinFlowRules.normalizeBaseUrl("https://"));
        assertNull(SorinFlowRules.normalizeBaseUrl(""));
        assertNull(SorinFlowRules.normalizeBaseUrl(null));
    }

    @Test
    public void endpointUrlsAppendTheFixedPaths() {
        assertEquals("https://sorinflow.com/api/scraper/otp-inbound",
                SorinFlowRules.otpUrl("https://sorinflow.com"));
        assertEquals("https://sorinflow.com/api/scraper/forwarder-heartbeat",
                SorinFlowRules.heartbeatUrl("https://sorinflow.com"));
    }
}
