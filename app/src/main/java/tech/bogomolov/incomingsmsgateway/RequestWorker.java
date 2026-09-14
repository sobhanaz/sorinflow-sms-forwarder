package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public class RequestWorker extends Worker {

    public final static String DATA_URL = "URL";
    public final static String DATA_TEXT = "TEXT";
    public final static String DATA_HEADERS = "HEADERS";
    public final static String DATA_IGNORE_SSL = "IGNORE_SSL";
    public final static String DATA_MAX_RETRIES = "MAX_RETRIES";
    public final static String DATA_CHUNKED_MODE = "CHUNKED_MODE";
    public final static String DATA_SIGN_HMAC_SHA256 = "SIGN_HMAC_SHA256";
    public final static String DATA_SIGN_HMAC_SHA256_SECRET = "SIGN_HMAC_SHA256_SECRET";
    public final static String DATA_STORE_FAILED = "STORE_FAILED";
    public final static String DATA_LOCAL_MODE = "LOCAL_MODE";
    // True for SorinFlow rules: the HMAC secret is read from the encrypted
    // SorinFlowSettings at send time instead of travelling in this Data (which
    // WorkManager persists in plain SQLite).
    public final static String DATA_SIGN_WITH_SETUP_SECRET = "SIGN_WITH_SETUP_SECRET";
    // Epoch millis the SMS reached the device; lets the status card show the true
    // SMS-to-server latency.
    public final static String DATA_RECEIVED_STAMP = "RECEIVED_STAMP";
    // Epoch millis after which the message must not be sent (one-time codes). When
    // present the worker retries on RetrySchedule's ladder within a single run
    // instead of WorkManager's backoff.
    public final static String DATA_DEADLINE = "DEADLINE";

    public RequestWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Enqueues a delivery with the standard "wait for network + exponential
     * backoff" policy. Shared by the live SMS path ({@link DirectDelivery}, once
     * the immediate attempt failed) and the manual retry path
     * ({@link FailedMessage#retryAll}).
     */
    public static void enqueue(Context context, Data data) {
        // "Local network mode" (issue #83): NetworkType.CONNECTED requires a
        // *validated* internet connection, so forwarding to a LAN endpoint on a
        // Wi-Fi without upstream internet never fires. When the config opts into
        // local mode we drop the constraint (NOT_REQUIRED) so the request runs as
        // soon as it is enqueued instead of waiting for internet that never comes.
        boolean localMode = data.getBoolean(DATA_LOCAL_MODE, false);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(localMode ? NetworkType.NOT_REQUIRED : NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest.Builder builder =
                new OneTimeWorkRequest.Builder(RequestWorker.class)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS
                        )
                        .setInputData(data);

        // Expedited work starts within seconds even in Doze. Below API 31 it would
        // have to run as a foreground service with its own notification, so it
        // stays a regular request there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        }

        WorkManager.getInstance(context).enqueue(builder.build());
    }

    @NonNull
    @Override
    public Result doWork() {
        Data input = getInputData();
        boolean storeFailed = input.getBoolean(DATA_STORE_FAILED, false);

        long deadline = input.getLong(DATA_DEADLINE, 0L);
        if (deadline > 0L) {
            return deliverBeforeDeadline(input, deadline, storeFailed);
        }

        int maxRetries = input.getInt(DATA_MAX_RETRIES, 10);
        if (getRunAttemptCount() > maxRetries) {
            return fail(storeFailed);
        }

        String result = attempt(getApplicationContext(), input,
                Request.DEFAULT_CONNECT_TIMEOUT_MS, Request.DEFAULT_READ_TIMEOUT_MS);

        if (result.equals(Request.RESULT_RETRY)) {
            return Result.retry();
        }

        if (result.equals(Request.RESULT_ERROR)) {
            return fail(storeFailed);
        }

        return Result.success();
    }

    // Time-critical deliveries (one-time codes) retry on RetrySchedule's short
    // ladder inside this single run: WorkManager's backoff has a 10 s minimum plus
    // unpredictable scheduling latency, which cannot honour a 100 s cutoff. Once
    // the deadline passes the code is useless, so it is stored as failed, never sent.
    private Result deliverBeforeDeadline(Data input, long deadline, boolean storeFailed) {
        for (int retry = 0; ; retry++) {
            if (RetrySchedule.isExpired(System.currentTimeMillis(), deadline)) {
                Log.e("RequestWorker", "deadline passed, not sending stale code");
                return fail(storeFailed);
            }

            String result = attempt(getApplicationContext(), input,
                    Request.DEFAULT_CONNECT_TIMEOUT_MS, DirectDelivery.READ_TIMEOUT_MS);
            if (result.equals(Request.RESULT_SUCCESS)) {
                return Result.success();
            }
            if (result.equals(Request.RESULT_ERROR)) {
                return fail(storeFailed);
            }

            long delay = RetrySchedule.nextDelayMillis(retry, System.currentTimeMillis(), deadline);
            if (delay < 0L) {
                Log.e("RequestWorker", "giving up: next retry would pass the deadline");
                return fail(storeFailed);
            }
            // If WorkManager stops us (constraints lost), hand the rest back to it;
            // the deadline check at the top of the loop still applies on re-run.
            if (isStopped()) {
                return Result.retry();
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return Result.retry();
            }
            if (isStopped()) {
                return Result.retry();
            }
        }
    }

    /**
     * One HTTP attempt built from worker input Data, with its outcome recorded for
     * the status card. Shared with the immediate path in {@link DirectDelivery}.
     */
    static String attempt(Context context, Data input, int connectTimeoutMs, int readTimeoutMs) {
        String url = input.getString(DATA_URL);
        String text = input.getString(DATA_TEXT);
        String headers = input.getString(DATA_HEADERS);
        boolean ignoreSsl = input.getBoolean(DATA_IGNORE_SSL, false);
        boolean useChunkedMode = input.getBoolean(DATA_CHUNKED_MODE, true);
        boolean signHmacSha256 = input.getBoolean(DATA_SIGN_HMAC_SHA256, false);
        String signHmacSha256Secret = input.getString(DATA_SIGN_HMAC_SHA256_SECRET);
        if (input.getBoolean(DATA_SIGN_WITH_SETUP_SECRET, false)) {
            signHmacSha256Secret = SorinFlowSettings.load(context).getSecret();
        }

        Request request = new Request(url, text);
        request.setTimeouts(connectTimeoutMs, readTimeoutMs);
        request.setJsonHeaders(headers);
        // A null/empty secret can't be signed with (and would throw, which makes
        // WorkManager fail the job *without* running the store-failed path). Send
        // unsigned instead: the endpoint's auth rejection stays visible in the
        // syslog via the logged response code.
        if (signHmacSha256 && signHmacSha256Secret != null && !signHmacSha256Secret.isEmpty()) {
            request.setSignatureHeader(signHmacSha256Secret, text);
        } else if (signHmacSha256) {
            Log.e("RequestWorker", "HMAC signing enabled but no secret stored; sending unsigned");
        }

        request.setIgnoreSsl(ignoreSsl);
        request.setUseChunkedMode(useChunkedMode);

        String result = request.execute();
        DeliveryStatus.recordMessage(context, input, request, result);
        return result;
    }

    // Permanent failure: optionally persist the payload for manual retry, then
    // report failure so WorkManager stops retrying.
    private Result fail(boolean storeFailed) {
        if (storeFailed) {
            FailedMessage.save(getApplicationContext(), getInputData());
        }
        DeliveryStatus.markFailed(getApplicationContext());
        return Result.failure();
    }
}
