package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Signed requests to the SorinFlow server that are not SMS deliveries: the periodic
 * heartbeat and the manual "send test" from the status card. Both reuse the template
 * engine (for %battery%, %network%, %version%) and the same X-Signature HMAC as the
 * forwarding rules.
 */
public final class SorinFlowClient {

    public interface Callback {
        void onResult(Request request, String result);
    }

    private SorinFlowClient() {
    }

    static String heartbeatBody(Context context, String account) {
        ForwardingConfig template = new ForwardingConfig(context);
        template.setTemplate(SorinFlowRules.heartbeatTemplate(account));
        return template.prepareMessage("", "", "", System.currentTimeMillis());
    }

    static String testBody(Context context, SorinFlowSettings settings) {
        ForwardingConfig template = new ForwardingConfig(context);
        template.setTemplate(SorinFlowRules.messageTemplate(SorinFlowRules.KIND_TEST, settings.getAccount()));
        // No "Code:" in the text, so the code field stays empty and nothing can be matched.
        return template.prepareMessage(SorinFlowRules.SENDER, "SorinFlow Forwarder test message",
                "", System.currentTimeMillis());
    }

    /** Blocking signed POST; call off the main thread. */
    static Request post(String url, String body, String secret, int connectTimeoutMs, int readTimeoutMs) {
        Request request = new Request(url, body);
        request.setTimeouts(connectTimeoutMs, readTimeoutMs);
        request.setJsonHeaders(ForwardingConfig.getDefaultJsonHeaders());
        request.setSignatureHeader(secret, body);
        request.setUseChunkedMode(false);
        request.execute();
        return request;
    }

    /**
     * Runs on the caller's (heartbeat) thread. A dual-SIM phone pings once per
     * account so the panel shows both online; the card reflects the first.
     */
    static String sendHeartbeat(Context context, SorinFlowSettings settings) {
        String url = SorinFlowRules.heartbeatUrl(settings.getBaseUrl());
        Request request = post(url, heartbeatBody(context, settings.getAccount()),
                settings.getSecret(), Request.DEFAULT_CONNECT_TIMEOUT_MS, Request.DEFAULT_READ_TIMEOUT_MS);
        DeliveryStatus.recordHeartbeat(context, request, request.getResult());
        if (settings.hasSecondAccount()) {
            post(url, heartbeatBody(context, settings.getAccount2()),
                    settings.getSecret(), Request.DEFAULT_CONNECT_TIMEOUT_MS, Request.DEFAULT_READ_TIMEOUT_MS);
        }
        return request.getResult();
    }

    /**
     * Posts a kind:"test" payload on a background thread; the callback runs on the
     * main thread. The outcome is recorded as the server-reachability result (the
     * card's "server" line), not as a forwarded message, so the last real Divar
     * delivery stays visible.
     */
    public static void sendTest(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            SorinFlowSettings settings = SorinFlowSettings.load(app);
            String body = testBody(app, settings);
            Request request = post(SorinFlowRules.otpUrl(settings.getBaseUrl()), body,
                    settings.getSecret(), DirectDelivery.CONNECT_TIMEOUT_MS, DirectDelivery.READ_TIMEOUT_MS);
            DeliveryStatus.recordHeartbeat(app, request, request.getResult());
            DeliveryLog.append(app, DeliveryLog.build(body, 0L, request, request.getResult()));
            main.post(() -> callback.onResult(request, request.getResult()));
        }, "SorinFlowTest").start();
    }
}
