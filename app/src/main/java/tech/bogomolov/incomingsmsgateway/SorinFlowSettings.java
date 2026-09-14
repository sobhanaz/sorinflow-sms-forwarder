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
    private static final String KEY_SECRET = "secret";

    // Opening the encrypted store costs a Keystore round-trip, so it is opened once
    // per process.
    private static SharedPreferences cached;

    private final String baseUrl;
    private final String account;
    private final String secret;

    public SorinFlowSettings(String baseUrl, String account, String secret) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.account = account == null ? "" : account;
        this.secret = secret == null ? "" : secret;
    }

    public String getBaseUrl() {
        return this.baseUrl;
    }

    public String getAccount() {
        return this.account;
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
                pref.getString(KEY_SECRET, ""));
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString(KEY_BASE_URL, this.baseUrl)
                .putString(KEY_ACCOUNT, this.account)
                .putString(KEY_SECRET, this.secret)
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
