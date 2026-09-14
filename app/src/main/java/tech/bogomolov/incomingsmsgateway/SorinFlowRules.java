package tech.bogomolov.incomingsmsgateway;

import android.content.Context;

import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and installs the two forwarding rules (Divar contact-info code, Divar login
 * code) plus the signed heartbeat that make this app a SorinFlow forwarder. The rules
 * use fixed keys, so re-running setup updates them in place instead of duplicating.
 * The builders take no Android state beyond the Context handed to ForwardingConfig,
 * so the templates and URL handling are unit-tested on the JVM.
 */
public final class SorinFlowRules {

    static final String KEY_PREFIX = "sorinflow_";
    public static final String KEY_CONTACT = KEY_PREFIX + "contact";
    public static final String KEY_LOGIN = KEY_PREFIX + "login";
    // Dual-SIM: the first pair is then bound to SIM 1 and this pair to SIM 2.
    public static final String KEY_CONTACT_SIM2 = KEY_PREFIX + "contact_sim2";
    public static final String KEY_LOGIN_SIM2 = KEY_PREFIX + "login_sim2";

    public static final String SENDER = "Divar";
    public static final String FILTER_CONTACT = "اطلاعات تماس";
    public static final String FILTER_LOGIN = "کد تایید";
    public static final String KIND_CONTACT = "contact";
    public static final String KIND_LOGIN = "login";
    public static final String KIND_TEST = "test";

    public static final String OTP_PATH = "/api/scraper/otp-inbound";
    public static final String HEARTBEAT_PATH = "/api/scraper/forwarder-heartbeat";

    /** Extracts the six Latin digits after "Code:"; the server re-extracts from text if this is empty. */
    static final String CODE_PLACEHOLDER = "%Regex=Code:\\s*(\\d{6})%";

    /** Names this phone to the server, which then verifies the signature with the phone's own secret. */
    public static final String HEADER_DEVICE_ID = "X-Forwarder-Id";

    private SorinFlowRules() {
    }

    /**
     * Human name for a SorinFlow rule in the rule list (the two rules would
     * otherwise both read "Divar" + the same URL); null for hand-made rules.
     */
    public static String displayName(Context context, ForwardingConfig config) {
        String key = config.getKey();
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return null;
        }
        String name = context.getString(key.startsWith(KEY_CONTACT)
                ? R.string.rule_name_contact : R.string.rule_name_login);
        if (config.getSimSlot() > 0) {
            name += " · SIM " + config.getSimSlot();
        }
        return name;
    }

    /** True for the rules installed by setup; they sign with the setup secret and use the fast retry ladder. */
    public static boolean isSorinFlowRule(ForwardingConfig config) {
        String key = config.getKey();
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /**
     * Trims, requires https://, and drops a trailing slash or an accidentally pasted
     * endpoint path. Returns null when the value is not a usable https base URL.
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.trim().replaceAll("/+$", "");
        for (String suffix : new String[]{OTP_PATH, HEARTBEAT_PATH}) {
            if (url.endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
            }
        }
        url = url.replaceAll("/+$", "");
        if (!url.startsWith("https://")) {
            return null;
        }
        try {
            if (new URL(url).getHost().isEmpty()) {
                return null;
            }
        } catch (MalformedURLException e) {
            return null;
        }
        return url;
    }

    public static String otpUrl(String baseUrl) {
        return baseUrl + OTP_PATH;
    }

    public static String heartbeatUrl(String baseUrl) {
        return baseUrl + HEARTBEAT_PATH;
    }

    /** Body template for the otp-inbound endpoint; %text% and the code are JSON-escaped by prepareMessage. */
    public static String messageTemplate(String kind, String account) {
        return "{\"kind\":\"" + kind + "\","
                + "\"account\":\"" + StringEscapeUtils.escapeJson(account) + "\","
                + "\"code\":\"" + CODE_PLACEHOLDER + "\","
                + "\"text\":\"%text%\","
                + "\"sim\":\"%sim%\","
                + "\"sentStamp\":%sentStamp%,"
                + "\"receivedStamp\":%receivedStamp%,"
                + "\"battery\":%battery%,"
                + "\"network\":\"%network%\"}";
    }

    /** Body template for the forwarder-heartbeat endpoint. */
    public static String heartbeatTemplate(String account) {
        return "{\"account\":\"" + StringEscapeUtils.escapeJson(account) + "\","
                + "\"battery\":%battery%,"
                + "\"network\":\"%network%\","
                + "\"version\":\"%version%\"}";
    }

    /** Request headers for every SorinFlow request: the user agent plus X-Forwarder-Id when the phone has a panel device id. */
    public static String headersJson(String deviceId) {
        JSONObject headers = new JSONObject();
        try {
            headers.put("User-Agent", "SorinFlow Forwarder/" + BuildConfig.VERSION_NAME);
            if (deviceId != null && !deviceId.isEmpty()) {
                headers.put(HEADER_DEVICE_ID, deviceId);
            }
        } catch (JSONException e) {
            return ForwardingConfig.getDefaultJsonHeaders();
        }
        return headers.toString();
    }

    static ForwardingConfig buildRule(Context context, String key, String kind, String filter,
                                      SorinFlowSettings settings) {
        return buildRule(context, key, kind, filter, settings.getBaseUrl(), settings.getAccount(), 0,
                settings.getDeviceId());
    }

    static ForwardingConfig buildRule(Context context, String key, String kind, String filter,
                                      String baseUrl, String account, int simSlot, String deviceId) {
        ForwardingConfig config = new ForwardingConfig(context);
        config.setKey(key);
        config.setSender(SENDER);
        config.setIsSenderRegex(false);
        config.setSmsFilter(filter);
        config.setUrl(otpUrl(baseUrl));
        config.setSimSlot(simSlot);
        config.setTemplate(messageTemplate(kind, account));
        config.setHeaders(headersJson(deviceId));
        config.setRetriesNumber(ForwardingConfig.getDefaultRetriesNumber());
        config.setIgnoreSsl(false);
        config.setChunkedMode(false);
        config.setIsSmsEnabled(true);
        // Signed, but the secret is resolved from SorinFlowSettings at send time so
        // it never sits in the plain-text rule store or a rules export.
        config.setSignHmacSha256(true);
        config.setSignHmacSha256Secret(null);
        config.setStoreFailed(true);
        config.setLocalMode(false);
        return config;
    }

    /**
     * One account: two rules matching any SIM. Two accounts (dual-SIM phone): the
     * same two rules bound to SIM slot 1 plus a second pair bound to slot 2, each
     * pair carrying its own account.
     */
    public static List<ForwardingConfig> buildRules(Context context, SorinFlowSettings settings) {
        List<ForwardingConfig> rules = new ArrayList<>();
        String base = settings.getBaseUrl();
        String device = settings.getDeviceId();
        if (!settings.hasSecondAccount()) {
            rules.add(buildRule(context, KEY_CONTACT, KIND_CONTACT, FILTER_CONTACT, base, settings.getAccount(), 0, device));
            rules.add(buildRule(context, KEY_LOGIN, KIND_LOGIN, FILTER_LOGIN, base, settings.getAccount(), 0, device));
            return rules;
        }
        rules.add(buildRule(context, KEY_CONTACT, KIND_CONTACT, FILTER_CONTACT, base, settings.getAccount(), 1, device));
        rules.add(buildRule(context, KEY_LOGIN, KIND_LOGIN, FILTER_LOGIN, base, settings.getAccount(), 1, device));
        rules.add(buildRule(context, KEY_CONTACT_SIM2, KIND_CONTACT, FILTER_CONTACT, base, settings.getAccount2(), 2, device));
        rules.add(buildRule(context, KEY_LOGIN_SIM2, KIND_LOGIN, FILTER_LOGIN, base, settings.getAccount2(), 2, device));
        return rules;
    }

    /**
     * Creates or updates the rules (removing the SIM 2 pair when it is no longer
     * wanted) and switches on the signed heartbeat (every 5 minutes).
     */
    public static void apply(Context context, SorinFlowSettings settings) {
        for (ForwardingConfig rule : buildRules(context, settings)) {
            rule.save();
        }
        if (!settings.hasSecondAccount()) {
            for (String key : new String[]{KEY_CONTACT_SIM2, KEY_LOGIN_SIM2}) {
                ForwardingConfig stale = new ForwardingConfig(context);
                stale.setKey(key);
                stale.remove();
            }
        }
        new HeartbeatSettings(true, heartbeatUrl(settings.getBaseUrl()),
                HeartbeatSettings.DEFAULT_INTERVAL_MINUTES).save(context);
    }

    /** The HMAC secret a rule signs with: the setup secret for SorinFlow rules, the rule's own otherwise. */
    public static String resolveSecret(Context context, ForwardingConfig config) {
        if (isSorinFlowRule(config)) {
            return SorinFlowSettings.load(context).getSecret();
        }
        return config.getSignHmacSha256Secret();
    }
}
