package tech.bogomolov.incomingsmsgateway;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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
        card.bind();
        return card;
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
        account.setText(settings.isConfigured()
                ? context.getString(R.string.status_account, settings.getAccount())
                : context.getString(R.string.status_not_configured));

        TextView server = view.findViewById(R.id.status_server);
        long heartbeatTime = status.getLong(DeliveryStatus.KEY_HB_TIME, 0L);
        if (heartbeatTime == 0L) {
            server.setText(R.string.status_server_none);
        } else {
            String ago = DateUtils.getRelativeTimeSpanString(heartbeatTime, now,
                    DateUtils.SECOND_IN_MILLIS).toString();
            if (status.getBoolean(DeliveryStatus.KEY_HB_OK, false)) {
                server.setText(context.getString(R.string.status_server_ok,
                        status.getInt(DeliveryStatus.KEY_HB_HTTP, -1), ago));
            } else {
                String reason = status.getString(DeliveryStatus.KEY_HB_REASON, "");
                server.setText(context.getString(R.string.status_server_fail,
                        reason.isEmpty() ? "HTTP " + status.getInt(DeliveryStatus.KEY_HB_HTTP, -1) : reason,
                        ago));
            }
        }

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
