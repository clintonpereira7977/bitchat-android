package app.chitchat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Receives only hints. Chat plaintext and envelopes never pass through FCM. */
public final class ChitChatMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) { OnlinePush.recordRotatedToken(this, token); }
    @Override public void onMessageReceived(RemoteMessage message) {
        PushNotifications.show(this, message.getData().get("kind"));
    }
    @Override public void onDeletedMessages() {
        // Fetch the signed inbox the next time Online opens; never silently
        // open/decrypt a chat or keep its transcript in a background service.
    }
}
