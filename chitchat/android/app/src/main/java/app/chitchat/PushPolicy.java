package app.chitchat;

/** Only a generic wake-up hint belongs in a notification. Never display a
 * provider-supplied title/body, nickname, message preview or envelope. */
final class PushPolicy {
    static String body(boolean enabled, String kind) {
        if (!enabled || kind == null) return null;
        switch (kind) {
            case "inbox": return "A new message is waiting.";
            case "requests": return "You have a new chat request.";
            case "contacts": return "Your connection request has an update.";
            case "test": return "This is a ChitChat notification test.";
            default: return null;
        }
    }
    static String result(String code) {
        if (code == null) return "The notification check hasn’t completed.";
        switch (code) {
            case "ACCEPTED": return "Google accepted the notification request. This doesn’t confirm it appeared on your phone.";
            case "PERMISSION_DENIED": return "The server’s Firebase sender lacks permission, or the Firebase Cloud Messaging API is disabled. The app owner needs to check the sender’s project and role.";
            case "SENDER_ID_MISMATCH": return "The server’s Firebase project doesn’t match this Android app. The app owner needs to correct the sender configuration.";
            case "INVALID_CONFIGURATION": case "AUTHENTICATION_FAILED": case "UNAUTHENTICATED": return "The server’s Firebase credential could not authenticate. The app owner needs to check its runtime secret.";
            case "UNREGISTERED": return "Google no longer recognizes this phone’s notification registration. Refresh notification registration and try again.";
            case "NO_DEVICE_TOKEN": return "This phone hasn’t completed notification registration. Choose Finish notification setup.";
            case "NOT_CONFIGURED": return "The server’s Firebase sender isn’t configured yet.";
            case "QUOTA_EXCEEDED": return "Google’s notification limit was reached. Try again later.";
            case "NOT_CHECKED": return "The server hasn’t checked this phone’s notification registration yet.";
            default: return "The notification provider is unavailable or rejected the request. Try again shortly.";
        }
    }
    private PushPolicy() {}
}
