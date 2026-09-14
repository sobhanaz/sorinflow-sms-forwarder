package tech.bogomolov.incomingsmsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.work.Data;

import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SmsBroadcastReceiver extends BroadcastReceiver {

    private Context context;

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }

        StringBuilder content = new StringBuilder();
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; i++) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            content.append(messages[i].getDisplayMessageBody());
        }

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);
        String asterisk = context.getString(R.string.asterisk);

        String sender = messages[0].getOriginatingAddress();
        if (sender == null) {
            return;
        }

        for (ForwardingConfig config : configs) {
            if (!matchesSender(config, sender, asterisk)) {
                continue;
            }

            if (!config.getIsSmsEnabled()) {
                continue;
            }

            if (!matchesFilter(config.getSmsFilter(), content.toString())) {
                continue;
            }

            int slotId = this.detectSim(bundle) + 1;
            String slotName = "undetected";
            if (slotId < 0) {
                slotId = 0;
            }

            if (config.getSimSlot() > 0 && config.getSimSlot() != slotId) {
                continue;
            }

            if (slotId > 0) {
                slotName = "sim" + slotId;
            }

            this.callWebHook(config, sender, slotName, content.toString(), messages[0].getTimestampMillis());
        }
    }

    protected void callWebHook(ForwardingConfig config, String sender, String slotName,
                               String content, long timeStamp) {

        String message = config.prepareMessage(sender, content, slotName, timeStamp);
        long receivedStamp = System.currentTimeMillis();

        // SorinFlow rules sign with the secret from the encrypted setup store (never
        // copied into this Data) and must reach the server before the code expires.
        boolean sorinFlow = SorinFlowRules.isSorinFlowRule(config);

        Data.Builder data = new Data.Builder()
                .putString(RequestWorker.DATA_URL, config.getUrl())
                .putString(RequestWorker.DATA_TEXT, message)
                .putString(RequestWorker.DATA_HEADERS, config.getHeaders())
                .putBoolean(RequestWorker.DATA_IGNORE_SSL, config.getIgnoreSsl())
                .putBoolean(RequestWorker.DATA_CHUNKED_MODE, config.getChunkedMode())
                .putInt(RequestWorker.DATA_MAX_RETRIES, config.getRetriesNumber())
                .putBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, config.getSignHmacSha256() || sorinFlow)
                .putString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET,
                        sorinFlow ? null : config.getSignHmacSha256Secret())
                .putBoolean(RequestWorker.DATA_SIGN_WITH_SETUP_SECRET, sorinFlow)
                .putBoolean(RequestWorker.DATA_STORE_FAILED, config.getStoreFailed())
                .putBoolean(RequestWorker.DATA_LOCAL_MODE, config.getLocalMode())
                .putLong(RequestWorker.DATA_RECEIVED_STAMP, receivedStamp);
        if (sorinFlow) {
            data.putLong(RequestWorker.DATA_DEADLINE, RetrySchedule.deadlineFor(receivedStamp));
        }

        // Immediate attempt on a background thread; WorkManager is only the fallback.
        DirectDelivery.send(this.context, data.build());
    }

    // Per-config sender match. The asterisk wildcard always means "any sender"
    // regardless of the regex flag. When the rule opts into regex matching (issue
    // #88 — e.g. an Indian sender ID like AB-CTAXKR whose operator prefix rotates),
    // the configured sender is a Java regex tested against the incoming address with
    // find() (substring), mirroring the content filter. Unlike the content filter
    // this fails *closed*: an invalid pattern matches nothing, so a typo cannot leak
    // unrelated senders to the endpoint. The default (flag off) is the historic
    // exact String.equals match, so every existing stored rule is unchanged.
    static boolean matchesSender(ForwardingConfig config, String sender, String asterisk) {
        String configured = config.getSender();
        if (configured.equals(asterisk)) {
            return true;
        }
        if (config.getIsSenderRegex()) {
            try {
                return Pattern.compile(configured).matcher(sender).find();
            } catch (PatternSyntaxException e) {
                Log.e("SmsBroadcastReceiver",
                        "Invalid sender regex \"" + configured + "\": " + e.getMessage());
                return false;
            }
        }
        return sender.equals(configured);
    }

    // Per-config content filter (issue #52). An empty filter forwards every
    // message (the historic behaviour). A non-empty filter is a Java regex tested
    // against the SMS body with find() (substring match): the message is forwarded
    // only when the regex matches. The single regex covers both directions —
    // "OTP" forwards messages that contain OTP, while a negative-lookahead such as
    // "(?s)^(?!.*OTP)" forwards every message that does NOT contain it. An invalid
    // pattern fails open (forwards and logs) so a typo never silently drops SMS,
    // mirroring the "never crash forwarding" rule used by the %Regex% placeholder.
    static boolean matchesFilter(String filter, String content) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        try {
            return Pattern.compile(filter).matcher(content).find();
        } catch (PatternSyntaxException e) {
            Log.e("SmsBroadcastReceiver", "Invalid filter regex \"" + filter + "\": " + e.getMessage());
            return true;
        }
    }

    private int detectSim(Bundle bundle) {
        int slotId = -1;
        Set<String> keySet = bundle.keySet();
        for (String key : keySet) {
            switch (key) {
                case "phone":
                    slotId = bundle.getInt("phone", -1);
                    break;
                case "slot":
                    slotId = bundle.getInt("slot", -1);
                    break;
                case "simId":
                    slotId = bundle.getInt("simId", -1);
                    break;
                case "simSlot":
                    slotId = bundle.getInt("simSlot", -1);
                    break;
                case "slot_id":
                    slotId = bundle.getInt("slot_id", -1);
                    break;
                case "simnum":
                    slotId = bundle.getInt("simnum", -1);
                    break;
                case "slotId":
                    slotId = bundle.getInt("slotId", -1);
                    break;
                case "slotIdx":
                    slotId = bundle.getInt("slotIdx", -1);
                    break;
                case "android.telephony.extra.SLOT_INDEX":
                    slotId = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
                    break;
                default:
                    if (key.toLowerCase().contains("slot") | key.toLowerCase().contains("sim")) {
                        String value = bundle.getString(key, "-1");
                        if (value.equals("0") | value.equals("1") | value.equals("2")) {
                            slotId = bundle.getInt(key, -1);
                        }
                    }
            }

            if (slotId != -1) {
                break;
            }
        }

        return slotId;
    }
}
