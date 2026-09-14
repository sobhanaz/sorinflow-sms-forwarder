package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.Data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Persists the payload of webhook deliveries that exhausted all retries, so the
 * user can re-send them later from the UI. Storage is opt-in per forwarding rule
 * (see {@link ForwardingConfig} "store failed"): only deliveries whose worker
 * input {@link Data} carries {@link RequestWorker#DATA_STORE_FAILED}=true are kept
 * here. Each entry is one SharedPreferences record keyed by a generated
 * timestamp_random key, holding the worker input Data serialized as JSON — enough
 * to re-enqueue the exact same request.
 */
public class FailedMessage {

    private static final String PREFERENCE = "failed_messages";

    // Oldest entries are dropped once this many are stored, so a long offline
    // period (or a permanently-broken endpoint) can't grow the store without bound.
    static final int MAX_STORED = 500;

    public static void save(Context context, Data data) {
        try {
            JSONObject json = dataToJson(data);

            SharedPreferences.Editor editor = getPreference(context).edit();
            editor.putString(generateKey(), json.toString());
            editor.commit();

            trim(context);
        } catch (Exception e) {
            Log.e("FailedMessage", String.valueOf(e.getMessage()));
        }
    }

    public static int getCount(Context context) {
        return getPreference(context).getAll().size();
    }

    public static List<Data> getAll(Context context) {
        List<Data> messages = new ArrayList<>();
        for (Object value : getPreference(context).getAll().values()) {
            try {
                messages.add(jsonToData(new JSONObject((String) value)));
            } catch (JSONException e) {
                Log.e("FailedMessage", String.valueOf(e.getMessage()));
            }
        }
        return messages;
    }

    public static void clear(Context context) {
        SharedPreferences.Editor editor = getPreference(context).edit();
        editor.clear();
        editor.commit();
    }

    /**
     * Re-enqueues every stored message and empties the store. Anything that fails
     * again is re-saved by {@link RequestWorker} on the way out (its input Data
     * still carries DATA_STORE_FAILED=true), so no per-item bookkeeping is needed.
     */
    public static void retryAll(Context context) {
        List<Data> messages = getAll(context);
        clear(context);
        for (Data data : messages) {
            RequestWorker.enqueue(context, data);
        }
    }

    // Keeps only the newest MAX_STORED entries. Keys are timestamp-prefixed, so
    // natural string order is (approximately) chronological — oldest first.
    private static void trim(Context context) {
        SharedPreferences pref = getPreference(context);
        Map<String, ?> all = pref.getAll();
        if (all.size() <= MAX_STORED) {
            return;
        }

        TreeMap<String, ?> sorted = new TreeMap<>(all);
        int toRemove = all.size() - MAX_STORED;
        SharedPreferences.Editor editor = pref.edit();
        for (String key : sorted.keySet()) {
            if (toRemove-- <= 0) {
                break;
            }
            editor.remove(key);
        }
        editor.commit();
    }

    static JSONObject dataToJson(Data data) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(RequestWorker.DATA_URL, data.getString(RequestWorker.DATA_URL));
        json.put(RequestWorker.DATA_TEXT, data.getString(RequestWorker.DATA_TEXT));
        json.put(RequestWorker.DATA_HEADERS, data.getString(RequestWorker.DATA_HEADERS));
        json.put(RequestWorker.DATA_IGNORE_SSL, data.getBoolean(RequestWorker.DATA_IGNORE_SSL, false));
        json.put(RequestWorker.DATA_CHUNKED_MODE, data.getBoolean(RequestWorker.DATA_CHUNKED_MODE, true));
        json.put(RequestWorker.DATA_MAX_RETRIES, data.getInt(RequestWorker.DATA_MAX_RETRIES, 10));
        json.put(RequestWorker.DATA_SIGN_HMAC_SHA256, data.getBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, false));
        json.put(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET, data.getString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET));
        json.put(RequestWorker.DATA_STORE_FAILED, data.getBoolean(RequestWorker.DATA_STORE_FAILED, false));
        json.put(RequestWorker.DATA_LOCAL_MODE, data.getBoolean(RequestWorker.DATA_LOCAL_MODE, false));
        json.put(RequestWorker.DATA_SIGN_WITH_SETUP_SECRET, data.getBoolean(RequestWorker.DATA_SIGN_WITH_SETUP_SECRET, false));
        json.put(RequestWorker.DATA_RECEIVED_STAMP, data.getLong(RequestWorker.DATA_RECEIVED_STAMP, 0L));
        // DATA_DEADLINE is deliberately not kept: a manual retry is the user's call,
        // and the server rejects a stale code itself (reason "stale_code").
        return json;
    }

    static Data jsonToData(JSONObject json) {
        return new Data.Builder()
                .putString(RequestWorker.DATA_URL, json.optString(RequestWorker.DATA_URL, null))
                .putString(RequestWorker.DATA_TEXT, json.optString(RequestWorker.DATA_TEXT, null))
                .putString(RequestWorker.DATA_HEADERS, json.optString(RequestWorker.DATA_HEADERS, null))
                .putBoolean(RequestWorker.DATA_IGNORE_SSL, json.optBoolean(RequestWorker.DATA_IGNORE_SSL, false))
                .putBoolean(RequestWorker.DATA_CHUNKED_MODE, json.optBoolean(RequestWorker.DATA_CHUNKED_MODE, true))
                .putInt(RequestWorker.DATA_MAX_RETRIES, json.optInt(RequestWorker.DATA_MAX_RETRIES, 10))
                .putBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, json.optBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, false))
                .putString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET, json.optString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET, null))
                .putBoolean(RequestWorker.DATA_STORE_FAILED, json.optBoolean(RequestWorker.DATA_STORE_FAILED, false))
                // Local mode must survive the round-trip, or a retried message
                // regains the validated-internet constraint and a LAN-only
                // delivery never runs (see RequestWorker.enqueue).
                .putBoolean(RequestWorker.DATA_LOCAL_MODE, json.optBoolean(RequestWorker.DATA_LOCAL_MODE, false))
                .putBoolean(RequestWorker.DATA_SIGN_WITH_SETUP_SECRET, json.optBoolean(RequestWorker.DATA_SIGN_WITH_SETUP_SECRET, false))
                .putLong(RequestWorker.DATA_RECEIVED_STAMP, json.optLong(RequestWorker.DATA_RECEIVED_STAMP, 0L))
                .build();
    }

    private static SharedPreferences getPreference(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }

    private static String generateKey() {
        String stamp = Long.toString(System.currentTimeMillis());
        int randomNum = new Random().nextInt((999990 - 100000) + 1) + 100000;
        return stamp + '_' + randomNum;
    }
}
