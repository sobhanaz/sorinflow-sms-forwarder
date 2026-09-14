package tech.bogomolov.incomingsmsgateway;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

/**
 * The ROM-level switches no app can flip for itself (Xiaomi/HyperOS battery
 * saver "No restrictions", Autostart, locking in Recents, RCS off), as a
 * checklist with buttons that open the exact pages. Everything the app can do
 * on its own (foreground service, boot and keepalive restarts, the Android
 * battery-optimisation exemption) is already automatic.
 */
final class KeepAliveGuide {

    private KeepAliveGuide() {
    }

    /** True on Xiaomi, Redmi and POCO phones, whose ROM needs the manual switches. */
    static boolean isXiaomi() {
        String maker = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase();
        return maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
    }

    static void show(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.keepalive_guide_title)
                .setMessage(R.string.keepalive_guide_message)
                .setPositiveButton(R.string.btn_battery_settings, (d, w) -> openAppDetails(activity))
                .setNeutralButton(R.string.btn_autostart, (d, w) -> openAutostart(activity))
                .setNegativeButton(R.string.btn_close, null)
                .show();
    }

    // App info page: on HyperOS/MIUI it holds "Battery saver" and the permissions.
    static void openAppDetails(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // MIUI's autostart manager; other ROMs fall back to the app info page.
    static void openAutostart(Activity activity) {
        Intent intent = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            openAppDetails(activity);
        }
    }
}
