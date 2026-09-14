package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The last {@link #MAX_ENTRIES} delivery attempts (real codes, manual retries and
 * test requests), newest first, with the HTTP status and the server's reason.
 * Stored as one JSON array in its own SharedPreferences file and shown by
 * {@link DeliveryLogActivity}. Heartbeats are not logged here (they would flood
 * the list); their outcome lives in {@link DeliveryStatus}.
 */
public final class DeliveryLog {

    static final String PREFERENCE = "delivery_log";
    static final String KEY_ENTRIES = "entries";
    static final int MAX_ENTRIES = 20;

    public static final class Entry {
        public final long time;
        public final String kind;
        public final String code;
        public final String sim;
        public final int http;
        public final long rttMs;
        public final long sinceReceivedMs;
        public final String result;
        public final String reason;

        public Entry(long time, String kind, String code, String sim, int http, long rttMs,
                     long sinceReceivedMs, String result, String reason) {
            this.time = time;
            this.kind = kind == null ? "" : kind;
            this.code = code == null ? "" : code;
            this.sim = sim == null ? "" : sim;
            this.http = http;
            this.rttMs = rttMs;
            this.sinceReceivedMs = sinceReceivedMs;
            this.result = result == null ? "" : result;
            this.reason = reason == null ? "" : reason;
        }

        Entry withResult(String newResult) {
            return new Entry(time, kind, code, sim, http, rttMs, sinceReceivedMs, newResult, reason);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("time", time).put("kind", kind).put("code", code).put("sim", sim)
                    .put("http", http).put("rtt", rttMs).put("since", sinceReceivedMs)
                    .put("result", result).put("reason", reason);
        }

        static Entry fromJson(JSONObject json) {
            return new Entry(json.optLong("time"), json.optString("kind"), json.optString("code"),
                    json.optString("sim"), json.optInt("http", -1), json.optLong("rtt", -1L),
                    json.optLong("since", -1L), json.optString("result"), json.optString("reason"));
        }
    }

    private DeliveryLog() {
    }

    /** Builds the entry for one attempt from the payload sent and the request outcome. */
    static Entry build(String payload, long receivedStamp, Request request, String result) {
        String kind = "";
        String code = "";
        String sim = "";
        try {
            JSONObject json = new JSONObject(payload == null ? "" : payload);
            kind = json.optString("kind", "");
            code = json.optString("code", "");
            sim = json.optString("sim", "");
            if (code.isEmpty()) {
                code = OtpCodes.extract(json.optString("text", ""));
            }
        } catch (JSONException ignored) {
            // Not one of our JSON templates.
        }
        long now = System.currentTimeMillis();
        return new Entry(now, kind, OtpCodes.mask(code), sim, request.getResponseCode(),
                request.getElapsedMillis(), receivedStamp > 0 ? now - receivedStamp : -1L,
                Request.RESULT_SUCCESS.equals(result) ? DeliveryStatus.RESULT_OK : DeliveryStatus.RESULT_RETRYING,
                DeliveryStatus.reason(request));
    }

    public static synchronized void append(Context context, Entry entry) {
        List<Entry> entries = getAll(context);
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        store(context, entries);
    }

    /** Marks the newest entry (the last attempt of the abandoned message) as failed. */
    public static synchronized void markNewestFailed(Context context) {
        List<Entry> entries = getAll(context);
        if (entries.isEmpty()) {
            return;
        }
        entries.set(0, entries.get(0).withResult(DeliveryStatus.RESULT_FAILED));
        store(context, entries);
    }

    /** Newest first. */
    public static List<Entry> getAll(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_ENTRIES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                entries.add(Entry.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e("DeliveryLog", "corrupt log, starting over: " + e);
        }
        return entries;
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_ENTRIES).commit();
    }

    private static void store(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
        } catch (JSONException e) {
            Log.e("DeliveryLog", "cannot serialize log: " + e);
            return;
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).commit();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }
}
