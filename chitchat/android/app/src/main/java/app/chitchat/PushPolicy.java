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
            default: return null;
        }
    }
    private PushPolicy() {}
}
