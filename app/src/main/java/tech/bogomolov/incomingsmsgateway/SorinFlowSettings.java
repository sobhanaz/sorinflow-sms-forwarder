package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * The three values that bind this phone to a SorinFlow server: the server base URL,
 * the Divar account (phone number) whose SIM sits in this device, and the shared
 * secret every request is signed with. Kept in EncryptedSharedPreferences (AES-GCM,
 * key in the Android Keystore) in a file of its own, separate from the plain-text
 * rule store, so the secret never lands in a backup or a rules export. The rules and
 * WorkManager jobs carry a "sign with the setup secret" flag instead of the value.
 */
public class SorinFlowSettings {

    private static final String PREFERENCE = "sorinflow_secure";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_ACCOUNT2 = "account2";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_DEVICE_ID = "device_id";

    // Opening the encrypted store costs a Keystore round-trip, so it is opened once
    // per process.
    private static SharedPreferences cached;

    private final String baseUrl;
    private final String account;
    private final String account2;
    private final String secret;
    private final String deviceId;

    public SorinFlowSettings(String baseUrl, String account, String secret) {
        this(baseUrl, account, "", secret, "");
    }

    /** account2 is the Divar account of the SIM in slot 2 on a dual-SIM phone; empty when unused. */
    public SorinFlowSettings(String baseUrl, String account, String account2, String secret) {
        this(baseUrl, account, account2, secret, "");
    }

    /**
     * deviceId is the panel's per-phone id (sent as X-Forwarder-Id so the server
     * checks this phone's own secret); empty means the server's shared secret.
     */
    public SorinFlowSettings(String baseUrl, String account, String account2, String secret,
                             String deviceId) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.account = account == null ? "" : account;
        this.account2 = account2 == null ? "" : account2;
        this.secret = secret == null ? "" : secret;
        this.deviceId = deviceId == null ? "" : deviceId.trim();
    }

    public String getBaseUrl() {
        return this.baseUrl;
    }

    public String getAccount() {
        return this.account;
    }

    public String getAccount2() {
        return this.account2;
    }

    public boolean hasSecondAccount() {
        return !this.account2.isEmpty();
    }

    public String getDeviceId() {
        return this.deviceId;
    }

    public String getSecret() {
        return this.secret;
    }

    public boolean isConfigured() {
        return !this.baseUrl.isEmpty() && !this.account.isEmpty() && !this.secret.isEmpty();
    }

    public static SorinFlowSettings load(Context context) {
        SharedPreferences pref = prefs(context);
        return new SorinFlowSettings(
                pref.getString(KEY_BASE_URL, ""),
                pref.getString(KEY_ACCOUNT, ""),
                pref.getString(KEY_ACCOUNT2, ""),
                pref.getString(KEY_SECRET, ""),
                pref.getString(KEY_DEVICE_ID, ""));
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString(KEY_BASE_URL, this.baseUrl)
                .putString(KEY_ACCOUNT, this.account)
                .putString(KEY_ACCOUNT2, this.account2)
                .putString(KEY_SECRET, this.secret)
                .putString(KEY_DEVICE_ID, this.deviceId)
                .commit();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().commit();
    }

    private static synchronized SharedPreferences prefs(Context context) {
        if (cached != null) {
            return cached;
        }
        Context app = context.getApplicationContext();
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            cached = EncryptedSharedPreferences.create(
                    app,
                    PREFERENCE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // ponytail: a broken Keystore (rare, OEM-specific) degrades to plain storage
            // on this device instead of a dead app; the secret still never leaves the
            // phone. Upgrade path: surface the failure on the status card.
            Log.e("SorinFlowSettings", "encrypted storage unavailable, using plain prefs: " + e);
            cached = app.getSharedPreferences(PREFERENCE + "_plain", Context.MODE_PRIVATE);
        }
        return cached;
    }
}
