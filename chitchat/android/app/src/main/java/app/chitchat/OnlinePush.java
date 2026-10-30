package app.chitchat;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.messaging.FirebaseMessaging;

/** Notification plumbing for the signed-in Online client. The
 * Bluetooth Activity deliberately never calls enableForRegisteredAccount.
 * FCM auto-init stays OFF even after opt-in; refresh explicitly on foreground
 * Online sessions. A failed or denied notification setup never blocks chat. */
final class OnlinePush {
    static final String PREFS = "chitchat_online_push";
    interface TokenResult { void complete(String token, boolean available); }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    static synchronized boolean enabled(Context context) {
        return prefs(context).getBoolean("enabled", false)
                && !prefs(context).getString("account", "").isEmpty();
    }
    static synchronized boolean ready(Context context) {
        return enabled(context) && !prefs(context).getString("registered_token", "").isEmpty()
                && prefs(context).getString("pending_token", "").isEmpty();
    }
    static synchronized void enableForRegisteredAccount(Context context, String account) {
        if (account == null || !account.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("A registered Online account is required");
        SharedPreferences p = prefs(context);
        if (!account.equals(p.getString("account", ""))) p.edit().clear().commit();
        p.edit().putString("account", account).putBoolean("enabled", true).commit();
    }
    static synchronized void disable(Context context) {
        // The Online client must also DELETE /v1/push using signed auth.
        // No Firebase SDK call here: Nearby mode must not trigger registration.
        PushNotifications.clear(context);
        prefs(context).edit().clear().commit();
    }
    static synchronized void recordRotatedToken(Context context, String token) {
        if (enabled(context) && validToken(token)) {
            SharedPreferences.Editor edit = prefs(context).edit().putString("pending_token", token);
            if (!fingerprint(token).equals(prefs(context).getString("registered_token", ""))) edit.remove("registered_token");
            edit.apply();
        }
        // The foreground signed Online client uploads pending_token.
        // A push callback never registers an anonymous or different account.
    }
    static synchronized String pendingToken(Context context) {
        return enabled(context) ? prefs(context).getString("pending_token", "") : "";
    }
    static synchronized void acknowledgeToken(Context context, String token) {
        if (token.equals(pendingToken(context))) prefs(context).edit().remove("pending_token").putString("registered_token", fingerprint(token)).apply();
    }
    private static String fingerprint(String token) {
        try { return OnlineCrypto.b64(OnlineCrypto.hash(OnlineCrypto.utf8(token))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static void requestToken(Context context, TokenResult result) {
        Context app = context.getApplicationContext();
        final String account;
        synchronized (OnlinePush.class) {
            if (!enabled(app)) { result.complete(null, false); return; }
            account = prefs(app).getString("account", "");
        }
        try {
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            messaging.setAutoInitEnabled(false);
            messaging.setDeliveryMetricsExportToBigQuery(false);
            messaging.getToken().addOnCompleteListener(task -> {
                String token = task.isSuccessful() ? task.getResult() : null;
                synchronized (OnlinePush.class) {
                    if (!enabled(app) || !account.equals(prefs(app).getString("account", "")) || !validToken(token)) {
                        result.complete(null, false); return;
                    }
                    recordRotatedToken(app, token);
                }
                result.complete(token, true);
            });
        } catch (RuntimeException ignored) { result.complete(null, false); }
    }
    private static boolean validToken(String token) {
        return token != null && token.length() >= 20 && token.length() <= 4096
                && !token.matches("(?s).*[\\s\\p{Cntrl}].*");
    }
    private OnlinePush() {}
}
