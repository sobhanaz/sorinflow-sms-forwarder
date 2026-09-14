package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sideloaded apps never auto-update, so at most every {@link #INTERVAL_MS} the app
 * asks the GitHub Releases API for the latest tag of {@link BuildConfig#UPDATE_REPO}
 * and the status card offers the download when it is newer than this build.
 * Results go into DeliveryStatus' preferences so the card re-binds on its own.
 */
public final class UpdateCheck {

    static final long INTERVAL_MS = 6 * 60 * 60_000L;
    static final String KEY_LATEST_TAG = "update_latest_tag";
    static final String KEY_LATEST_URL = "update_latest_url";
    static final String KEY_CHECKED_AT = "update_checked_at";

    private UpdateCheck() {
    }

    public static void maybeCheck(Context context) {
        if (BuildConfig.UPDATE_REPO.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        SharedPreferences prefs = DeliveryStatus.prefs(app);
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0L) < INTERVAL_MS) {
            return;
        }
        // Stamp first so a slow or failing request isn't repeated on every resume.
        prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
        new Thread(() -> check(app), "UpdateCheck").start();
    }

    private static void check(Context context) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest")
                    .openConnection();
            connection.setConnectTimeout(Request.DEFAULT_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Request.DEFAULT_READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "SorinFlow Forwarder/" + BuildConfig.VERSION_NAME);
            if (connection.getResponseCode() != 200) {
                Log.e("UpdateCheck", "releases api answered " + connection.getResponseCode());
                return;
            }
            JSONObject release = new JSONObject(readAll(connection.getInputStream()));
            String tag = release.optString("tag_name", "");
            String url = release.optString("html_url", "");
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name", "").endsWith(".apk")) {
                    url = asset.optString("browser_download_url", url);
                    break;
                }
            }
            if (!tag.isEmpty()) {
                DeliveryStatus.prefs(context).edit()
                        .putString(KEY_LATEST_TAG, tag)
                        .putString(KEY_LATEST_URL, url)
                        .commit();
            }
        } catch (IOException | JSONException | ClassCastException e) {
            Log.e("UpdateCheck", "update check failed: " + e);
        }
    }

    /** True when latestTag (e.g. "v3.1.0") is a higher numeric version than current ("3.0.0"). */
    public static boolean isNewer(String latestTag, String current) {
        int[] latest = parse(latestTag);
        int[] installed = parse(current);
        if (latest == null || installed == null) {
            return false;
        }
        for (int i = 0; i < Math.max(latest.length, installed.length); i++) {
            int a = i < latest.length ? latest[i] : 0;
            int b = i < installed.length ? installed[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        // Drop a pre-release or build suffix such as "-beta1" or "+main.42".
        for (char separator : new char[]{'-', '+'}) {
            int cut = v.indexOf(separator);
            if (cut >= 0) {
                v = v.substring(0, cut);
            }
        }
        String[] parts = v.split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers.length == 0 ? null : numbers;
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder body = new StringBuilder();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1 && body.length() < 256 * 1024) {
                body.append(buffer, 0, read);
            }
        }
        return body.toString();
    }
}
