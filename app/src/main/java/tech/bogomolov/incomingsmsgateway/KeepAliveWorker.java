package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Belt and braces for OEM battery managers: every 15 minutes (WorkManager's
 * minimum period) restart {@link SmsReceiverService} if something killed it. On
 * Android 12+ the restart only succeeds while the app is exempt from battery
 * optimisation, which the status card asks the user for.
 */
public class KeepAliveWorker extends Worker {

    static final String UNIQUE_NAME = "keepalive";

    public KeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(KeepAliveWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean wanted = !ForwardingConfig.getAll(context).isEmpty()
                || HeartbeatSettings.load(context).isEnabled();
        if (wanted && !SmsReceiverService.isRunning(context)) {
            Log.i("KeepAliveWorker", "service not running, restarting it");
            SmsReceiverService.start(context);
        }
        return Result.success();
    }
}
