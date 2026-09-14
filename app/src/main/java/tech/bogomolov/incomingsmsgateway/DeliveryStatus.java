package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Last-known outcome of the newest delivery and of the newest heartbeat, for the
 * status card on the main screen. Lives in its own SharedPreferences file; the card
 * registers a change listener on it, so a result written from the delivery thread
 * shows up on screen without polling. Codes are stored masked.
 */
public final class DeliveryStatus {

    static final String PREFERENCE = "delivery_status";

    static final String KEY_MSG_TIME = "msg_time";
    static final String KEY_MSG_KIND = "msg_kind";
    static final String KEY_MSG_CODE = "msg_code_masked";
    static final String KEY_MSG_HTTP = "msg_http";
    static final String KEY_MSG_RTT = "msg_rtt_ms";
    static final String KEY_MSG_SINCE_RECEIVED = "msg_since_received_ms";
    static final String KEY_MSG_RESULT = "msg_result";
    static final String KEY_MSG_REASON = "msg_reason";

    static final String KEY_HB_TIME = "hb_time";
    static final String KEY_HB_HTTP = "hb_http";
    static final String KEY_HB_RTT = "hb_rtt_ms";
    static final String KEY_HB_OK = "hb_ok";
    static final String KEY_HB_REASON = "hb_reason";
    // When the current run of failed heartbeats/tests started; 0 while the server answers.
    static final String KEY_HB_FAILING_SINCE = "hb_failing_since";

    public static final String RESULT_OK = "ok";
    public static final String RESULT_RETRYING = "retrying";
    public static final String RESULT_FAILED = "failed";

    private DeliveryStatus() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }

    /** Records one delivery attempt made from worker input Data. */
    static void recordMessage(Context context, Data input, Request request, String result) {
        recordMessage(context, input.getString(RequestWorker.DATA_TEXT),
                input.getLong(RequestWorker.DATA_RECEIVED_STAMP, 0L), request, result);
    }

    static void recordMessage(Context context, String payload, long receivedStamp,
                              Request request, String result) {
        String kind = "";
        String code = "";
        try {
            JSONObject json = new JSONObject(payload == null ? "" : payload);
            kind = json.optString("kind", "");
            code = json.optString("code", "");
            if (code.isEmpty()) {
                code = OtpCodes.extract(json.optString("text", ""));
            }
        } catch (JSONException ignored) {
            // Not one of our JSON templates: no kind/code to show.
        }

        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putLong(KEY_MSG_TIME, now)
                .putString(KEY_MSG_KIND, kind)
                .putString(KEY_MSG_CODE, OtpCodes.mask(code))
                .putInt(KEY_MSG_HTTP, request.getResponseCode())
                .putLong(KEY_MSG_RTT, request.getElapsedMillis())
                .putLong(KEY_MSG_SINCE_RECEIVED, receivedStamp > 0 ? now - receivedStamp : -1L)
                .putString(KEY_MSG_RESULT,
                        Request.RESULT_SUCCESS.equals(result) ? RESULT_OK : RESULT_RETRYING)
                .putString(KEY_MSG_REASON, reason(request))
                .commit();
        DeliveryLog.append(context, DeliveryLog.build(payload, receivedStamp, request, result));
    }

    /** Called once a delivery is abandoned (deadline passed or permanent error). */
    static void markFailed(Context context) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString(KEY_MSG_RESULT, RESULT_FAILED).commit();
        DeliveryLog.markNewestFailed(context);
        Alerts.deliveryFailed(context, prefs.getString(KEY_MSG_KIND, ""), prefs.getString(KEY_MSG_REASON, ""));
    }

    static void recordHeartbeat(Context context, Request request, String result) {
        boolean ok = Request.RESULT_SUCCESS.equals(result);
        SharedPreferences prefs = prefs(context);
        long failingSince = ok ? 0L : prefs.getLong(KEY_HB_FAILING_SINCE, 0L);
        if (!ok && failingSince == 0L) {
            failingSince = System.currentTimeMillis();
        }
        String reason = reason(request);
        prefs.edit()
                .putLong(KEY_HB_TIME, System.currentTimeMillis())
                .putInt(KEY_HB_HTTP, request.getResponseCode())
                .putLong(KEY_HB_RTT, request.getElapsedMillis())
                .putBoolean(KEY_HB_OK, ok)
                .putString(KEY_HB_REASON, reason)
                .putLong(KEY_HB_FAILING_SINCE, failingSince)
                .commit();
        Alerts.serverCheck(context, ok, failingSince, reason);
    }

    /** The server's "reason"/"detail" field when it sent JSON, else the transport failure, else "". */
    static String reason(Request request) {
        try {
            JSONObject json = new JSONObject(request.getResponseBody());
            String reason = json.optString("reason", "");
            if (!reason.isEmpty()) {
                return reason;
            }
            String detail = json.optString("detail", "");
            if (!detail.isEmpty()) {
                return detail;
            }
        } catch (JSONException ignored) {
            // Non-JSON or empty body.
        }
        return request.getFailure();
    }
}
