package tech.bogomolov.incomingsmsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

// Keeps the foreground status-bar indicator alive and hosts the heartbeat ping. SMS
// delivery itself is handled by the manifest-declared SmsBroadcastReceiver (see
// AndroidManifest.xml / issue #78), so this service no longer registers an SMS
// receiver at runtime — doing so would double-deliver every message.
public class SmsReceiverService extends Service {

    private static final String CHANNEL_ID = "SmsDefault";
    private static final int NOTIFICATION_ID = 1;

    // Sent by SettingsActivity / SetupActivity after the heartbeat settings change,
    // so a running service re-reads them without a full restart (see onStartCommand).
    public static final String ACTION_RESCHEDULE_HEARTBEAT =
            "tech.bogomolov.incomingsmsgateway.RESCHEDULE_HEARTBEAT";

    // First ping shortly after (re)start, so the SorinFlow panel shows the phone
    // online right after setup or a reboot instead of one interval later.
    private static final long FIRST_HEARTBEAT_DELAY_MS = 5_000L;

    // The heartbeat runs on its own thread: hosting it in this foreground service
    // (rather than WorkManager, whose periodic minimum is 15 min) is what lets the
    // ping survive Doze at sub-15-min intervals, since a foreground service keeps
    // the process out of App Standby.
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;

    /**
     * Starts the service (or pokes a running one). Safe from any context: on
     * Android 12+ a start the OS refuses from the background is logged, not fatal.
     */
    public static void start(Context context) {
        start(context, null);
    }

    public static void start(Context context, @Nullable String action) {
        Intent intent = new Intent(context, SmsReceiverService.class);
        if (action != null) {
            intent.setAction(action);
        }
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(), intent);
        } catch (Exception e) {
            Log.e("SmsGateway", "cannot start foreground service: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        // IMPORTANCE_LOW keeps the "F" indicator silent (no sound, collapsed in
        // the shade) like the original IMPORTANCE_NONE, but is not created in a
        // blocked state — IMPORTANCE_NONE leaves the channel off on Android 13+,
        // which greys out the user's "Allow notifications" toggle (issue #77).
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getText(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(getColor(R.color.colorPrimary))
                        .setContentTitle(getText(R.string.app_name))
                        .setContentText(getText(R.string.notification_running))
                        .setContentIntent(openApp)
                        .setOngoing(true)
                        .build();

        try {
            // The foreground service type comes from the manifest (specialUse).
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            // Android 12+ refuses some background starts; Android 14 rejects a
            // missing type/permission. Don't crash-loop — SMS delivery still works
            // through the manifest receiver.
            Log.e("SmsGateway", "startForeground refused: " + e);
            stopSelf();
            return;
        }

        startHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESCHEDULE_HEARTBEAT.equals(intent.getAction())) {
            startHeartbeat();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopHeartbeat();
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    // Re-reads the heartbeat settings and (re)schedules the periodic ping. Safe to
    // call repeatedly: it first tears down any existing schedule.
    private void startHeartbeat() {
        stopHeartbeat();

        HeartbeatSettings settings = HeartbeatSettings.load(this);
        if (!settings.isEnabled() || settings.getUrl().isEmpty()) {
            return;
        }

        final String url = settings.getUrl();
        final long interval = settings.getIntervalMillis();

        heartbeatThread = new HandlerThread("HeartbeatThread");
        heartbeatThread.start();
        // The runnable keeps its own reference: stopHeartbeat() nulls the field
        // from the main thread while a ping may still be in flight, and posting
        // to a quit looper is a logged no-op rather than an NPE.
        final Handler handler = new Handler(heartbeatThread.getLooper());
        heartbeatHandler = handler;

        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat(url);
                handler.postDelayed(this, interval);
            }
        };

        handler.postDelayed(heartbeatRunnable, FIRST_HEARTBEAT_DELAY_MS);
    }

    private void stopHeartbeat() {
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        if (heartbeatThread != null) {
            heartbeatThread.quit();
            heartbeatThread = null;
        }
        heartbeatHandler = null;
        heartbeatRunnable = null;
    }

    // Runs on the heartbeat thread. The SorinFlow heartbeat carries a signed JSON
    // body (account, battery, network, version); any other URL gets the upstream
    // plain Content-Length: 0 POST so external monitors keep working.
    private void sendHeartbeat(String url) {
        try {
            SorinFlowSettings settings = SorinFlowSettings.load(this);
            if (settings.isConfigured() && url.equals(SorinFlowRules.heartbeatUrl(settings.getBaseUrl()))) {
                String result = SorinFlowClient.sendHeartbeat(this, settings);
                Log.i("SmsGateway", "sorinflow heartbeat: " + result);
                return;
            }

            Request request = new Request(url, "");
            request.setUseChunkedMode(false);
            String result = request.execute();
            Log.i("SmsGateway", "heartbeat: " + result);
        } catch (Exception e) {
            Log.e("SmsGateway", "heartbeat error: " + e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
