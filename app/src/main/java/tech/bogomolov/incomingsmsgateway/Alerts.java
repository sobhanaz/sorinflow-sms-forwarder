package tech.bogomolov.incomingsmsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Warning notifications on a high-importance channel: a code that could not be
 * delivered, and a server that has not answered a heartbeat (or test) for
 * {@link #UNREACHABLE_AFTER_MS}. The server warning is refreshed by every
 * heartbeat while the outage lasts and cleared by the first success.
 */
public final class Alerts {

    static final String CHANNEL_ID = "SorinFlowAlerts";
    static final int ID_DELIVERY_FAILED = 2;
    static final int ID_SERVER_UNREACHABLE = 3;
    static final long UNREACHABLE_AFTER_MS = 15 * 60_000L;

    private Alerts() {
    }

    static void deliveryFailed(Context context, String kind, String reason) {
        String text = context.getString(R.string.alert_delivery_failed_text,
                kind.isEmpty() ? "?" : kind, reason.isEmpty() ? "-" : reason);
        notify(context, ID_DELIVERY_FAILED, context.getString(R.string.alert_delivery_failed_title), text);
    }

    /**
     * Called after every heartbeat/test result. failingSince is 0 while the server
     * answers; once the silence exceeds the threshold the warning is shown with the
     * elapsed minutes and the last reason.
     */
    static void serverCheck(Context context, boolean ok, long failingSince, String reason) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (ok || failingSince == 0L) {
            manager.cancel(ID_SERVER_UNREACHABLE);
            return;
        }
        long silentFor = System.currentTimeMillis() - failingSince;
        if (silentFor < UNREACHABLE_AFTER_MS) {
            return;
        }
        String text = context.getString(R.string.alert_server_text,
                (int) (silentFor / 60_000L), reason.isEmpty() ? "-" : reason);
        notify(context, ID_SERVER_UNREACHABLE, context.getString(R.string.alert_server_title), text);
    }

    private static void notify(Context context, int id, String title, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                context.getText(R.string.alerts_channel), NotificationManager.IMPORTANCE_HIGH));

        PendingIntent openApp = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.colorDanger))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build();
        manager.notify(id, notification);
    }
}
