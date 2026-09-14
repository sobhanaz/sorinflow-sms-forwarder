package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.util.Log;

import androidx.work.Data;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends a webhook request right away on a background thread and falls back to
 * WorkManager only when that first attempt fails. WorkManager alone adds seconds of
 * scheduling latency (far more in Doze), which a one-time code that expires in about
 * two minutes cannot afford.
 */
public final class DirectDelivery {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;

    // One thread: deliveries are rare, and sequential order keeps the log readable.
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "DirectDelivery"));

    private DirectDelivery() {
    }

    public static void send(Context context, Data data) {
        final Context app = context.getApplicationContext();
        // A process cold-started just for the SMS broadcast drops to cached priority
        // the moment onReceive returns; (re)starting the foreground service keeps it
        // alive for this request and for the retry ladder that may follow.
        SmsReceiverService.start(app);

        EXECUTOR.execute(() -> {
            String result;
            try {
                result = RequestWorker.attempt(app, data, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            } catch (RuntimeException e) {
                Log.e("DirectDelivery", "direct attempt failed: " + e);
                result = Request.RESULT_RETRY;
            }

            if (Request.RESULT_SUCCESS.equals(result)) {
                return;
            }
            if (Request.RESULT_ERROR.equals(result)) {
                // Permanent (bad URL or headers): WorkManager would only repeat it.
                if (data.getBoolean(RequestWorker.DATA_STORE_FAILED, false)) {
                    FailedMessage.save(app, data);
                }
                DeliveryStatus.markFailed(app);
                return;
            }
            RequestWorker.enqueue(app, data);
        });
    }
}
