package tech.bogomolov.incomingsmsgateway;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the forwarder back after a reboot and after an update of the app
 * itself (MY_PACKAGE_REPLACED): installing a new APK kills the process, and the
 * foreground service would otherwise stay down until the keepalive job's next
 * 15-minute tick. Both broadcasts are sent by the system only.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent argIntent) {
        SmsReceiverService.start(context);
        KeepAliveWorker.schedule(context);
    }
}
