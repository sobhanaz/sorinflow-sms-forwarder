package tech.bogomolov.incomingsmsgateway;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * Connects this phone to a SorinFlow server: server URL (https only), the Divar
 * account whose SIM is in the phone (plus an optional second account for SIM 2),
 * and the shared secret. The fields can be filled by scanning the panel's QR code
 * or by opening a {@code sorinflow://setup} link; either way the user reviews and
 * taps Save. Saving stores them encrypted ({@link SorinFlowSettings}), installs the
 * Divar forwarding rules plus the signed heartbeat ({@link SorinFlowRules#apply}),
 * pokes the service and schedules the keepalive job.
 */
public class SetupActivity extends AppCompatActivity {

    // Iranian mobile numbers are 11 digits with the leading 0; shorter international
    // forms without the trunk prefix are still accepted.
    static final int MIN_PHONE_DIGITS = 10;

    private final ActivityResultLauncher<ScanOptions> scanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    applyPayload(result.getContents());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        SorinFlowSettings settings = SorinFlowSettings.load(this);
        // Official builds carry the server address (injected at build time from a
        // repository secret); a fresh install starts with it pre-filled.
        String baseUrl = settings.getBaseUrl().isEmpty()
                ? BuildConfig.DEFAULT_SERVER_URL : settings.getBaseUrl();
        fill(baseUrl, settings.getAccount(), settings.getAccount2(), settings.getSecret(),
                settings.getDeviceId());

        findViewById(R.id.btn_setup_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_scan_qr).setOnClickListener(v -> scanner.launch(new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)));

        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            applyPayload(intent.getData().toString());
        }
    }

    // Pre-fills the form from a scanned/opened setup payload; never saves by itself.
    private void applyPayload(String text) {
        SorinFlowSettings settings = SetupPayload.parse(text);
        if (settings == null) {
            Toast.makeText(this, R.string.setup_payload_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        fill(settings.getBaseUrl(), settings.getAccount(), settings.getAccount2(), settings.getSecret(),
                settings.getDeviceId());
        Toast.makeText(this, R.string.setup_payload_applied, Toast.LENGTH_LONG).show();
    }

    private void fill(String baseUrl, String account, String account2, String secret, String deviceId) {
        ((EditText) findViewById(R.id.input_server_url)).setText(baseUrl);
        ((EditText) findViewById(R.id.input_account_phone)).setText(account);
        ((EditText) findViewById(R.id.input_account_phone2)).setText(account2);
        ((EditText) findViewById(R.id.input_shared_secret)).setText(secret);
        ((EditText) findViewById(R.id.input_device_id)).setText(deviceId);
    }

    private void save() {
        SorinFlowSettings settings = readForm();
        if (settings == null) {
            return;
        }
        settings.save(this);
        SorinFlowRules.apply(this, settings);
        // Starts the service if needed and makes a running one re-read the heartbeat.
        SmsReceiverService.start(this, SmsReceiverService.ACTION_RESCHEDULE_HEARTBEAT);
        KeepAliveWorker.schedule(this);
        Toast.makeText(this, R.string.setup_saved_toast, Toast.LENGTH_LONG).show();
        finish();
    }

    // Validates the form and returns the entered settings, or null with an inline
    // error on the offending field.
    private SorinFlowSettings readForm() {
        final EditText urlInput = findViewById(R.id.input_server_url);
        String baseUrl = SorinFlowRules.normalizeBaseUrl(urlInput.getText().toString());
        if (baseUrl == null) {
            urlInput.setError(getString(R.string.error_https_url));
            return null;
        }

        final EditText phoneInput = findViewById(R.id.input_account_phone);
        String phone = OtpCodes.normalizePhone(phoneInput.getText().toString());
        if (phone.replace("+", "").length() < MIN_PHONE_DIGITS) {
            phoneInput.setError(getString(R.string.error_wrong_phone));
            return null;
        }

        final EditText phone2Input = findViewById(R.id.input_account_phone2);
        String phone2 = OtpCodes.normalizePhone(phone2Input.getText().toString());
        if (!phone2.isEmpty() && phone2.replace("+", "").length() < MIN_PHONE_DIGITS) {
            phone2Input.setError(getString(R.string.error_wrong_phone));
            return null;
        }

        final EditText secretInput = findViewById(R.id.input_shared_secret);
        String secret = secretInput.getText().toString().trim();
        if (secret.isEmpty()) {
            secretInput.setError(getString(R.string.error_empty_secret));
            return null;
        }

        String deviceId = ((EditText) findViewById(R.id.input_device_id)).getText().toString().trim();
        return new SorinFlowSettings(baseUrl, phone, phone2, secret, deviceId);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
