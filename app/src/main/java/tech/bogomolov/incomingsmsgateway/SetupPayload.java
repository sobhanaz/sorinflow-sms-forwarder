package tech.bogomolov.incomingsmsgateway;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses what a SorinFlow panel QR code (or a {@code sorinflow://setup} link)
 * carries into settings. Two shapes are accepted:
 * <pre>
 * sorinflow://setup?server=https://…&amp;account=0912…&amp;account2=0935…&amp;secret=…
 * {"server":"https://…","account":"0912…","account2":"0935…","secret":"…"}
 * </pre>
 * The result still goes through the setup form, so the user sees and confirms it.
 */
public final class SetupPayload {

    public static final String SCHEME = "sorinflow";
    public static final String HOST = "setup";

    private SetupPayload() {
    }

    /** Returns the settings in the payload, or null when it is not a setup payload. */
    public static SorinFlowSettings parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                return build(json.optString("server", ""), json.optString("account", ""),
                        json.optString("account2", ""), json.optString("secret", ""));
            } catch (JSONException e) {
                return null;
            }
        }
        String prefix = SCHEME + "://" + HOST;
        if (!trimmed.startsWith(prefix)) {
            return null;
        }
        String rest = trimmed.substring(prefix.length());
        int query = rest.indexOf('?');
        if (query < 0 || (query > 0 && !rest.substring(0, query).equals("/"))) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : rest.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return build(param(params, "server"), param(params, "account"),
                param(params, "account2"), param(params, "secret"));
    }

    private static String param(Map<String, String> params, String name) {
        String value = params.get(name);
        return value == null ? "" : value;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return value;
        }
    }

    private static SorinFlowSettings build(String server, String account, String account2, String secret) {
        String baseUrl = SorinFlowRules.normalizeBaseUrl(server);
        if (baseUrl == null) {
            return null;
        }
        return new SorinFlowSettings(baseUrl, OtpCodes.normalizePhone(account),
                OtpCodes.normalizePhone(account2), secret.trim());
    }
}
