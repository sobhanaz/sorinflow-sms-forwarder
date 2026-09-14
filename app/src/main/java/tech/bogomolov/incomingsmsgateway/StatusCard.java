package tech.bogomolov.incomingsmsgateway;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.Date;
import java.util.Locale;

/**
 * The SorinFlow status card shown above the rule list: account, last heartbeat,
 * last forwarded message (kind, masked code, HTTP status, round trip, delay since
 * the SMS arrived), stored failures, and the "send test to server" button. It
 * watches {@link DeliveryStatus}'s preferences so results written from delivery
 * threads appear without polling.
 */
final class StatusCard {

    private final Activity activity;
    private final View view;
    private boolean testRunning = false;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (prefs, key) -> bind();

    private StatusCard(Activity activity, View view) {
        this.activity = activity;
        this.view = view;
    }

    /** Inflates the card as a header of the rule list and wires its buttons. Call before setAdapter. */
    static StatusCard attach(Activity activity, ListView list) {
        View view = activity.getLayoutInflater().inflate(R.layout.status_card, list, false);
        list.addHeaderView(view, null, false);

        StatusCard card = new StatusCard(activity, view);
        view.findViewById(R.id.btn_setup).setOnClickListener(
                v -> activity.startActivity(new Intent(activity, SetupActivity.class)));
        view.findViewById(R.id.btn_send_test).setOnClickListener(v -> card.sendTest());
        view.findViewById(R.id.btn_allow_background).setOnClickListener(v -> card.requestBatteryExemption());
        View.OnClickListener openLog = v ->
                activity.startActivity(new Intent(activity, DeliveryLogActivity.class));
        view.findViewById(R.id.status_last).setOnClickListener(openLog);
        view.findViewById(R.id.status_last_detail).setOnClickListener(openLog);
        card.bind();
        return card;
    }

    // Opens the system dialog that puts the app on the battery-optimisation
    // allowlist, which is what lets KeepAliveWorker restart the service from the
    // background on Android 12+.
    private void requestBatteryExemption() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    void startWatching() {
        DeliveryStatus.prefs(activity).registerOnSharedPreferenceChangeListener(listener);
        bind();
    }

    void stopWatching() {
        DeliveryStatus.prefs(activity).unregisterOnSharedPreferenceChangeListener(listener);
    }

    void bind() {
        Context context = activity;
        SorinFlowSettings settings = SorinFlowSettings.load(context);
        SharedPreferences status = DeliveryStatus.prefs(context);
        long now = System.currentTimeMillis();

        TextView account = view.findViewById(R.id.status_account);
        if (!settings.isConfigured()) {
            account.setText(R.string.status_not_configured);
        } else if (settings.hasSecondAccount()) {
            account.setText(context.getString(R.string.status_accounts_two,
                    settings.getAccount(), settings.getAccount2()));
        } else {
            account.setText(context.getString(R.string.status_account, settings.getAccount()));
        }

        TextView server = view.findViewById(R.id.status_server);
        View dot = view.findViewById(R.id.status_server_dot);
        long heartbeatTime = status.getLong(DeliveryStatus.KEY_HB_TIME, 0L);
        int dotColor = R.color.colorMuted;
        if (heartbeatTime == 0L) {
            server.setText(R.string.status_server_none);
        } else {
            String ago = DateUtils.getRelativeTimeSpanString(heartbeatTime, now,
                    DateUtils.SECOND_IN_MILLIS).toString();
            if (status.getBoolean(DeliveryStatus.KEY_HB_OK, false)) {
                dotColor = R.color.colorSuccess;
                server.setText(context.getString(R.string.status_server_ok,
                        status.getInt(DeliveryStatus.KEY_HB_HTTP, -1), ago));
            } else {
                dotColor = R.color.colorDanger;
                String reason = status.getString(DeliveryStatus.KEY_HB_REASON, "");
                server.setText(context.getString(R.string.status_server_fail,
                        reason.isEmpty() ? "HTTP " + status.getInt(DeliveryStatus.KEY_HB_HTTP, -1) : reason,
                        ago));
            }
        }

        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, dotColor)));

        TextView last = view.findViewById(R.id.status_last);
        TextView lastDetail = view.findViewById(R.id.status_last_detail);
        long messageTime = status.getLong(DeliveryStatus.KEY_MSG_TIME, 0L);
        if (messageTime == 0L) {
            last.setText(R.string.status_last_none);
            lastDetail.setVisibility(View.GONE);
        } else {
            String kind = status.getString(DeliveryStatus.KEY_MSG_KIND, "");
            last.setText(context.getString(R.string.status_last_message,
                    kind.isEmpty() ? "?" : kind,
                    status.getString(DeliveryStatus.KEY_MSG_CODE, ""),
                    resultLabel(context, status.getString(DeliveryStatus.KEY_MSG_RESULT, ""))));

            long sinceReceived = status.getLong(DeliveryStatus.KEY_MSG_SINCE_RECEIVED, -1L);
            String reason = status.getString(DeliveryStatus.KEY_MSG_REASON, "");
            String when = DateFormat.getTimeFormat(context).format(new Date(messageTime))
                    + (reason.isEmpty() ? "" : " · " + reason);
            lastDetail.setText(context.getString(R.string.status_last_detail,
                    status.getInt(DeliveryStatus.KEY_MSG_HTTP, -1),
                    status.getLong(DeliveryStatus.KEY_MSG_RTT, -1L),
                    sinceReceived >= 0 ? formatSeconds(sinceReceived) : "–",
                    when));
            lastDetail.setVisibility(View.VISIBLE);
        }

        TextView failed = view.findViewById(R.id.status_failed);
        int failedCount = FailedMessage.getCount(context);
        failed.setVisibility(failedCount > 0 ? View.VISIBLE : View.GONE);
        if (failedCount > 0) {
            failed.setText(context.getString(R.string.status_failed_count, failedCount));
        }

        view.findViewById(R.id.btn_send_test).setEnabled(settings.isConfigured() && !testRunning);

        // Battery-optimisation warning: only once the phone is configured, and only
        // while Android may still throttle the app.
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean exempt = power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        view.findViewById(R.id.status_battery_row)
                .setVisibility(settings.isConfigured() && !exempt ? View.VISIBLE : View.GONE);

        // Update offer from the last GitHub Releases check.
        String latest = status.getString(UpdateCheck.KEY_LATEST_TAG, "");
        String latestUrl = status.getString(UpdateCheck.KEY_LATEST_URL, "");
        boolean newer = UpdateCheck.isNewer(latest, BuildConfig.VERSION_NAME) && !latestUrl.isEmpty();
        view.findViewById(R.id.status_update_row).setVisibility(newer ? View.VISIBLE : View.GONE);
        if (newer) {
            ((TextView) view.findViewById(R.id.status_update))
                    .setText(context.getString(R.string.status_update_available, latest));
            view.findViewById(R.id.btn_update).setOnClickListener(v ->
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(latestUrl))));
        }
    }

    private void sendTest() {
        testRunning = true;
        bind();
        Toast.makeText(activity, R.string.test_sending, Toast.LENGTH_SHORT).show();

        SorinFlowClient.sendTest(activity, (request, result) -> {
            testRunning = false;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            String reason = DeliveryStatus.reason(request);
            String message = Request.RESULT_SUCCESS.equals(result)
                    ? activity.getString(R.string.test_result_ok,
                            request.getResponseCode(), request.getElapsedMillis(), reason)
                    : activity.getString(R.string.test_result_fail,
                            reason.isEmpty() ? result : reason);
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            bind();
        });
    }

    private static String resultLabel(Context context, String result) {
        switch (result) {
            case DeliveryStatus.RESULT_OK:
                return context.getString(R.string.status_result_ok);
            case DeliveryStatus.RESULT_FAILED:
                return context.getString(R.string.status_result_failed);
            default:
                return context.getString(R.string.status_result_retrying);
        }
    }

    private static String formatSeconds(long millis) {
        return String.format(Locale.getDefault(), "%.1f s", millis / 1000f);
    }
}
