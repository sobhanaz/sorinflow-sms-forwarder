package tech.bogomolov.incomingsmsgateway;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Connects this phone to a SorinFlow server: server URL (https only), the Divar
 * account whose SIM is in the phone, and the shared secret. Saving stores them
 * encrypted ({@link SorinFlowSettings}), installs the two Divar forwarding rules
 * plus the signed heartbeat ({@link SorinFlowRules#apply}) and pokes the service.
 */
public class SetupActivity extends AppCompatActivity {

    // Iranian mobile numbers are 11 digits with the leading 0; shorter international
    // forms without the trunk prefix are still accepted.
    static final int MIN_PHONE_DIGITS = 10;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        SorinFlowSettings settings = SorinFlowSettings.load(this);
        ((EditText) findViewById(R.id.input_server_url)).setText(settings.getBaseUrl());
        ((EditText) findViewById(R.id.input_account_phone)).setText(settings.getAccount());
        ((EditText) findViewById(R.id.input_shared_secret)).setText(settings.getSecret());

        findViewById(R.id.btn_setup_save).setOnClickListener(v -> save());
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

        final EditText secretInput = findViewById(R.id.input_shared_secret);
        String secret = secretInput.getText().toString().trim();
        if (secret.isEmpty()) {
            secretInput.setError(getString(R.string.error_empty_secret));
            return null;
        }

        return new SorinFlowSettings(baseUrl, phone, secret);
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
